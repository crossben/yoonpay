# Stripe (cards)

Module: `yoon-providers/yoon-provider-stripe` · id: `stripe` · method: `card` · decision: [ADR-0022](../adr/0022-stripe-provider.md)

International cards through Stripe Checkout (Stripe's hosted payment page). You need a Stripe
account in a country Stripe supports; your customers can pay from anywhere.

> **Status:** built from Stripe's API reference and tested against a simulated API (WireMock).
> It has **not yet been run against a Stripe test account** — Stripe's test mode is free, so
> this is the easiest provider to check end to end.

## What Yoon uses

| Operation | Supported | How |
| --- | --- | --- |
| Collect | The countries and currencies you configure (default: the eight UEMOA countries, XOF) · method `card` | Checkout Session; the customer is sent to Stripe's page. Needs `return_url` |
| Refund | **Full and partial** | `POST /v1/refunds` on the session's PaymentIntent |
| Payout | **No** | Stripe pays out to your own bank account, not to recipients |

## Configuration

```sh
YOON_APPS_SHOP_PROVIDERS_STRIPE_PRIORITY=1
YOON_APPS_SHOP_PROVIDERS_STRIPE_CREDENTIALS_SECRET_KEY=sk_test_…        # or a restricted key: Checkout Sessions + Refunds write
YOON_APPS_SHOP_PROVIDERS_STRIPE_CREDENTIALS_WEBHOOK_SECRET=whsec_…
YOON_APPS_SHOP_PROVIDERS_STRIPE_CREDENTIALS_COUNTRIES=SN,CI            # optional; default BJ,BF,CI,GW,ML,NE,SN,TG
YOON_APPS_SHOP_PROVIDERS_STRIPE_CREDENTIALS_CURRENCIES=XOF,EUR         # optional; default XOF
```

Supported currencies: XOF, XAF, EUR, USD, GBP, CAD, CHF — those where Stripe's amount unit
matches ISO 4217. Others are refused at start-up instead of being charged at the wrong scale.

## Status mapping

Checkout Sessions (`GET /v1/checkout/sessions/{id}` → `status`, `payment_status`):

| Stripe | Yoon |
| --- | --- |
| `open` | pending |
| `complete` + `paid` | succeeded |
| `complete` + `unpaid` (a delayed payment method is settling) | pending |
| `expired` | expired |
| anything else | no answer |

Refunds (`GET /v1/refunds/{id}` → `status`):

| Stripe | Yoon |
| --- | --- |
| `pending`, `requires_action` | pending |
| `succeeded` | refunded |
| `failed`, `canceled` | failed |
| anything else | no answer |

Tested in `StripeStatusTest`.

## Quirks

- Every create sends Yoon's reference as `Idempotency-Key`: a repeated request never makes a
  second session or refund. A 409 (idempotency conflict) is **unknown**, never failed.
- Refusals carry Stripe's error code, surfaced as `stripe_<code>`, e.g. `stripe_amount_too_small`.
- Stripe's minimum charge (about 0.50 USD equivalent) applies.
- **Callbacks:** add a webhook endpoint in the Stripe Dashboard (Workbench → Webhooks) to
  `https://<YOON_PUBLIC_URL>/v1/hooks/stripe/<application id>` with the events
  `checkout.session.completed`, `checkout.session.async_payment_succeeded`,
  `checkout.session.async_payment_failed`, `checkout.session.expired`, `refund.updated`, and
  copy its `whsec_…` secret. Yoon checks `Stripe-Signature` (HMAC-SHA256 of `t.body`, only `v1`)
  and re-confirms through the API.
