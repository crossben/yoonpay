# Billing for SaaS — prepaid subscriptions with Yoon

Yoon 0.3.0 bills **prepaid subscriptions**: the customer pays each period (month, year…) on
Yoon's hosted checkout, by mobile money, PI-SPI or card, and your application grants access
from Yoon's webhooks. Nothing is charged automatically: mobile money has no card-style
auto-debit, so every period is a payment the customer makes. (Automatic charging, Stripe first,
is planned for 0.4.0.)

Design: [ADR-0025](adr/0025-customers-and-catalog.md) (customers, plans, prices),
[ADR-0026](adr/0026-subscriptions-prepaid.md) (subscriptions, lifecycle),
[ADR-0027](adr/0027-invoices-and-receipts.md) (invoices, receipts). Every write takes an
`Idempotency-Key`, as with payments.

## 1. Settings

Hosted checkout needs `YOON_PUBLIC_URL`. Per application (`<APP>` is the name given to
`apps create`, upper-cased):

| Variable | Default | Meaning |
| --- | --- | --- |
| `YOON_APPS_<APP>_BILLING_GRACE_DAYS` | `3` | Days a subscription stays `past_due` (still entitled) after its period ends unpaid |
| `YOON_APPS_<APP>_BILLING_REMINDER_DAYS` | `3` | Days before the period end when `subscription.renewal_upcoming` is sent |
| `YOON_APPS_<APP>_BILLING_TIMEZONE` | `Africa/Dakar` | Time zone for counting reminder days and invoice years |
| `YOON_APPS_<APP>_BILLING_INVOICE_PREFIX` | `INV` | Invoice numbers: `INV-2026-000001`, gap-free per application |
| `YOON_APPS_<APP>_BILLING_INVOICE_TTL` | `7d` | An unpaid first invoice older than this expires the subscription |

## 2. Catalog: plans and prices

```http
POST /v1/plans
Idempotency-Key: plan-pro

{"slug": "pro", "name": "Pro", "features": {"seats": 5, "exports": true},
 "prices": [{"currency": "XOF", "amount": 15000, "interval": "month"},
            {"currency": "XOF", "amount": 150000, "interval": "year"}]}
```

Amounts are integers in minor units. `features` is yours (limits, flags); every subscription
carries its plan's `features`. Prices never change (the database refuses edits): to change a
price, add a new one and archive the old one with `PATCH /v1/prices/{id}` `{"active": false}`.
Existing subscriptions keep their price.

## 3. Customers

Create one customer per user of yours, found again by your own id:

```http
POST /v1/customers
Idempotency-Key: customer-42

{"external_id": "42", "country": "SN", "email": "awa@example.com", "phone": "771234567"}
```

`409 customer_exists` means it already exists: `GET /v1/customers?external_id=42`.

## 4. Subscribe and send the customer to the checkout (FastAPI)

```python
from fastapi import APIRouter, Depends
from yoonpay import Yoon
from yoonpay.billing import Entitlement, changes_entitlement
from yoonpay.fastapi import NoCheckout, redirect_to_checkout, yoon_event, yoon_webhook_route
from yoonpay.generated import CreateSubscriptionRequest, RenewSubscriptionRequest

yoon = Yoon(os.environ["YOON_URL"], os.environ["YOON_API_KEY"])
billing = APIRouter()

@billing.post("/billing/subscribe/{price_id}")
def subscribe(price_id: str, user: User = Depends(current_user)):
    sub = yoon.call(lambda: yoon.subscriptions().create_subscription(
        f"subscribe-{user.id}-{price_id}",
        CreateSubscriptionRequest(customer=user.yoon_customer_id, price=price_id,
                                  return_url="https://app.example/billing")))
    user.yoon_subscription_id = sub.id
    return redirect_to_checkout(sub)          # 303 to latest_invoice.checkout_url

@billing.post("/billing/renew")
def renew(user: User = Depends(current_user)):
    invoice = yoon.call(lambda: yoon.subscriptions().renew_subscription(
        user.yoon_subscription_id, f"renew-{user.yoon_subscription_id}-{period_key()}",
        RenewSubscriptionRequest(return_url="https://app.example/billing")))
    return redirect_to_checkout(invoice)
```

The subscription starts `incomplete`; its first invoice has a `checkout_url`. **Never grant
access from the return URL** — only from webhooks (next section). `renew` returns the open
invoice (creating it if needed, at most one per subscription) with a fresh checkout when the
last one expired; customers may renew early, it never loses days.

JavaScript (Next.js route handler): `checkoutRedirect(sub)` from `@yoonpay/yoon/next`; in a
server action, `redirect(checkoutUrlOf(sub))`. See the [JS client README](../clients/js/README.md).

## 5. Grant access from webhooks

```python
webhooks = APIRouter(route_class=yoon_webhook_route(secret=os.environ["YOON_WEBHOOK_SECRET"]))

@webhooks.post("/yoon/webhook", status_code=204)
def yoon_events(event: Event = Depends(yoon_event)) -> None:
    if changes_entitlement(event.type):
        e = Entitlement.from_subscription(event.object)
        save_access(e.external_id, entitled=e.entitled, plan=e.plan, until=e.until, features=e.features)
    elif event.type == "subscription.renewal_upcoming":
        send_renewal_reminder(event.object)    # Yoon sends no e-mail
    elif event.type == "invoice.paid":
        send_receipt(event.object)             # links receipt_url
```

Every event carries the full object, so access can be driven from webhooks alone. Deliveries
are at least once: keep handlers idempotent (the helper drops exact duplicates). After an
outage on your side, reconcile with `GET /v1/subscriptions?customer=…`.

| Status | Meaning | Entitled |
| --- | --- | --- |
| `trialing` | Free trial (0.4), nothing paid yet | **yes** |
| `incomplete` | Created, first invoice not paid | no |
| `active` | Paid up to `current_period_end` | yes |
| `past_due` | Period ended unpaid, within the grace days; a renewal invoice is open | **yes** |
| `expired` | Grace ended, or the first invoice was never paid | no |
| `canceled` | Ended on request | no |

A late payment of an open invoice still re-activates an `expired` or `canceled` subscription:
money received is never ignored. New statuses or event types may appear in later versions;
the helpers treat unknown statuses as not entitled and never raise.

Events: `subscription.created|activated|renewed|changed|past_due|expired|canceled|renewal_upcoming|trial_will_end`,
`invoice.created|paid|voided`.

## 6. Free trials (0.4)

Give a plan a default trial with `"trial_days": 14` (on `POST /v1/plans` or `PATCH`), or pass
`trial_days` when subscribing (`0` for none). The subscription starts `trialing`: entitled, no
invoice, nothing to pay. Each customer gets one trial per plan. To keep access after the trial,
the customer pays through `POST /v1/subscriptions/{id}/renew` at any time during it — the paid
period starts when the trial ends, so no trial day is lost. `subscription.trial_will_end` arrives
the reminder days before; unpaid at the end, the subscription goes `past_due` (grace) with the
first invoice open, then `expired`.

**Upgrade your Yoon client to 0.4 before using trials**: the 0.3 helpers treat `trialing` as not
entitled and `subscription.created` as informational, so trial users would be denied access.

## 7. Periods, upgrades, cancellation

- **Paying early** stacks after the current period. **Paying in grace** starts from the old
  period end (grace days are not free). **Paying after expiry** starts at the payment.
- Months are calendar months anchored on the first day paid: a period from 31 January ends
  on 28/29 February, then 31 March.
- `POST /v1/subscriptions/{id}/change` `{"price": "price_…"}`: an **upgrade** (more per day)
  is invoiced now for a full period of the new price; once paid, the unused time of the old
  period becomes extra days. A **downgrade** or cheaper-per-day change waits for the period
  end. Changing currency is refused.
- `POST /v1/subscriptions/{id}/cancel`: at the period end by default (reversible with
  `/resume`), or `{"at_period_end": false}` to end now. No automatic refund.

## 8. Invoices and receipts

Each period is an invoice numbered without gaps. Once paid, `receipt_url` opens a printable
payment receipt (French/English). It is **a payment receipt, not a tax invoice**: if you must
issue tax invoices, produce them from `invoice.paid` in your own system. Exports:
`GET /v1/exports/subscriptions.csv`, `GET /v1/exports/invoices.csv`.

## 9. Operators: periods without payment (comp)

For partners, support gestures or "contact us" plans, without payment or ledger posting:

```sh
docker compose run --rm yoon billing comp <app> <sub_…|cus_…> <price_…> <periods|until|open> <note…>
```

or `POST /admin/v1/applications/{app}/subscriptions/{id}/comp` (see the
[runbook](runbook.md#grant-periods-without-payment-comp)). The subscription shows `comp: true`
until the customer pays a period.

## 10. Migrating from your own billing

If you built subscriptions on top of one-off payments:

1. Upgrade Yoon and set the billing settings above (your grace and reminder days).
2. Create plans and prices from your plan table; your limits become `features`.
3. Ship the webhook handler of section 5 next to your old one (both idempotent).
4. For each paying user: create the customer, then comp the time they already paid for,
   ending on their current expiry date: `billing comp <app> cus_… price_… 2026-12-31 "migration"`
   (or `"until"` in the admin API). Open-ended grants become `open`. From the next renewal,
   they pay through Yoon.
5. Switch your "Buy" buttons to `POST /v1/subscriptions` and `/renew`; keep the old payment
   path read-only until its pending payments have settled or expired.
