# ADR-0022 — Stripe provider (cards)

- Status: accepted
- Date: 2026-10-04

## Context
None of the African providers so far takes international cards with partial refunds. Stripe
does, through hosted Checkout, and it is the only provider here whose test mode anyone can use
for free, so the adapter can be checked end to end. Stripe does not onboard businesses in most
UEMOA countries: a merchant needs a Stripe account in a country Stripe supports. Customers can
pay from anywhere.

## Decision
A provider module `yoon-provider-stripe` (id `stripe`, method `card`).

| Yoon | Stripe API |
| --- | --- |
| Collect | `POST /v1/checkout/sessions` (form-encoded, `mode=payment`, one line item, `success_url` and `cancel_url` = the payment's `return_url`, `client_reference_id` and metadata `yoon_reference` = attempt reference), `Idempotency-Key` = attempt reference. The customer is sent to the session `url`. |
| Payment status | `GET /v1/checkout/sessions/{id}`: `status` + `payment_status` |
| Refund | `GET` the session for its `payment_intent`, then `POST /v1/refunds` with `amount` (partial allowed), metadata `yoon_reference`, `Idempotency-Key` = refund attempt reference; status `GET /v1/refunds/{id}` |
| Payout | None: Stripe pays out to the merchant's own bank account |
| Lost answers | Refunds: recent refunds searched for metadata `yoon_reference`. Sessions: no lookup needed — no money moves until the customer opens the URL, which they never received. |
| Callbacks | `Stripe-Signature: t=…,v1=…` = HMAC-SHA256 hex of `t + "." + raw body`; only `v1` counts. The record is found from `data.object.id`. |

### Currencies
Yoon stores minor units as ISO 4217 defines them; Stripe agrees for most currencies but not all
(ISK, UGX, HUF, TWD have special rules). The adapter accepts only currencies where both agree:
XOF, XAF, EUR, USD, GBP, CAD, CHF. Others are refused at configuration time rather than charged
at the wrong scale.

## Consequences
- Stripe's minimum charge amounts apply (e.g. about 0.50 USD equivalent); below them Stripe
  refuses the session, and the payment can fail over.
- The Stripe API version is the account's default; the adapter reads only long-stable fields.
- Tested against a simulated API from Stripe's reference, not yet against a Stripe test account.
