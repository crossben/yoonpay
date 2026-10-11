# Migrating from one-off payments to Yoon billing — OSIG as the reference case

Draft, written against OSIG's billing code as of 2026-10-11 (`osig/backend/app/services/billing_service.py`,
`models/billing.py`, `api/v1/billing.py`) and the 0.3.0 design (ADR-0025 to ADR-0027). It becomes the
"Migrating" section of the "Billing for SaaS" docs page when 0.3.0 ships.

## What OSIG built on top of `/v1/payments`, and where it goes

| OSIG today | With Yoon 0.3.0 |
| --- | --- |
| `plans` table: slug, name, rank, `prices` JSON (`{"XOF": {"month": 5000, …}}`), `limits` JSON | `POST /v1/plans` with `features` = OSIG's `limits`, one price per currency × period. OSIG keeps reading limits from the subscription's `features`. |
| Periods are fixed day counts: month 30, quarter 90, half 180, year 365 | Either keep exact day counts (`interval: day`, `interval_count: 30/90/180/365`), or switch to calendar periods (`month` × 1/3/6, `year`) anchored on the start day. Pick one per plan before migrating. |
| `rank` decides upgrade vs downgrade | Yoon compares **price per unit of time**. Same outcome for OSIG's plans as long as a higher plan costs more per day in the same currency; check `pro` vs `enterprise` yearly prices. |
| `payments` rows (`pending/paid/failed/expired`), created before the hosted checkout | Gone: a subscription's invoice owns its hosted payment. OSIG stores `yoon_subscription_id` on the user instead. |
| `settle()`: row lock, amount/currency match, idempotent | Done by Yoon (Settlement + invoice/subscription transitions in one transaction). |
| `activate()`: same plan stacks from expiry; upgrade starts now; downgrade queued in `pending_plan` | Same rules in Yoon, with one improvement: an upgrade converts the unused days of the old plan into extra days (OSIG drops them today). Buying the same plan with a longer period stacks (renewal at the pending price). |
| `users.plan_expires_at`, `pending_plan`, `pending_plan_days` | `current_period_end`, `pending_change` on the subscription. |
| `grant()` admin comp, incl. no end date (`days=None`) | `yoon billing comp … <periods | open>` or the admin API. |
| `expire_plans()` beat task (grace `BILLING_GRACE_DAYS=2`) | Yoon's billing sweep; set `YOON_APPS_OSIG_BILLING_GRACE_DAYS=2`. OSIG handles `subscription.expired` (switch the user to Free). |
| `send_renewal_reminders()` (7 days before) | `YOON_APPS_OSIG_BILLING_REMINDER_DAYS=7`; OSIG sends the e-mail on `subscription.renewal_upcoming`. |
| Receipt e-mail on payment | OSIG sends it on `invoice.paid`, linking Yoon's `receipt_url` if it wants. |
| `reconcile_pending()` polling Yoon | Not needed for settlement (Yoon reconciles with providers). OSIG still reconciles **its own** state after an outage: `GET /v1/subscriptions?customer=…`. |
| `yoon_events` table (dedupe) | Keep it: deliveries stay at-least-once. |
| `XOF_COUNTRIES` → XOF, else USD by card | Unchanged: OSIG picks the price (currency) when it creates the subscription. |

## Webhook handler after migration

```python
@webhooks.post("/yoon/webhook", status_code=204)
def yoon_events(event: Event = Depends(yoon_event), db: Session = Depends(get_db)):
    sub = event.object                      # the full subscription or invoice
    match event.type:
        case "subscription.activated" | "subscription.renewed" | "subscription.changed":
            entitle(db, external_id=sub["customer"]["external_id"], plan=sub["price"]["plan"]["slug"],
                    until=sub["current_period_end"], features=sub["features"])
        case "subscription.expired" | "subscription.canceled":
            downgrade_to_free(db, external_id=sub["customer"]["external_id"])
        case "subscription.renewal_upcoming":
            send_reminder(db, sub)
        case "invoice.paid":
            send_receipt(db, event.object)
```

(Field paths follow the 0.3.0 draft API and will be checked against the final contract.)

## Moving existing paying users

For each user with a running paid plan: create the customer (`external_id` = OSIG user id), then
grant the remaining time with an operator comp for that plan (no payment, `comp: true`), ending
at the user's current `plan_expires_at`. From the next renewal on, they pay through the
subscription. Users with an open-ended grant get `comp … open`. A dry-run script lists what it
would create before doing it.

## Order of work for OSIG
1. Upgrade Yoon to 0.3.0; set the per-application billing settings.
2. Create plans and prices (script from OSIG's `plans` table).
3. Ship the new webhook handler alongside the old one (both idempotent).
4. Migrate users (dry run, then for real).
5. Switch the "Buy" buttons to `POST /v1/subscriptions` / `renew`; keep the old payment path
   read-only until every pending one-off payment has settled or expired.
6. Remove OSIG's `payments` settlement code and beat tasks.
