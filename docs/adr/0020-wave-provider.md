# ADR-0020 — Wave provider (direct)

- Status: accepted
- Date: 2026-10-01

## Context
Wave is reachable today through PayDunya, DexPay and NabooPay. Wave also offers its own Business
API (docs.wave.com) to merchants with a Wave Business account: checkout sessions, payouts with
idempotency keys, full refunds of a checkout, and signed webhooks. Going direct removes an
intermediary (its fees, settlement delay and outages) and gives a refund API.

## Decision
A provider module `yoon-provider-wave` (id `wave`, method `wave`) — the same method name the
aggregators use, so Yoon can fail over between Wave direct and an aggregator's Wave.

| Yoon | Wave Business API |
| --- | --- |
| Collect | `POST /v1/checkout/sessions` with `client_reference` = attempt reference; the customer opens `wave_launch_url`. `success_url` and `error_url` are both the payment's `return_url` (required by Wave). A known customer phone is sent as `restrict_payer_mobile`. |
| Payment status | `GET /v1/checkout/sessions/{id}`: `payment_status` (`processing`/`succeeded`/`cancelled`) with `checkout_status` (`expired`) |
| Payout | `POST /v1/payout` with `Idempotency-Key` and `client_reference` = attempt reference; status `GET /v1/payout/{id}` |
| Refund | `POST /v1/checkout/sessions/{id}/refund`: full amount only, idempotent |
| Lost answers | `GET /v1/checkout/sessions/search` and `/v1/payouts/search` by `client_reference` |
| Callbacks | `Wave-Signature: t=…,v1=…` = HMAC-SHA256 hex of `t` + raw body; several `v1` during key rotation. The "shared secret" method (`Authorization: Bearer <secret>`) is accepted too. |

### Refund status
Wave has no refund status endpoint. Its refund call is idempotent ("no additional transaction"
on a second call) and answers 200 once the payment is refunded, so `refundStatus` repeats the
refund call: 200 → refunded. This is the one place a status check sends a POST; it is safe only
because Wave documents it as idempotent.

### Countries
Wave's XOF countries (SN, CI, ML, BF), chosen per application with the `countries` credential
(default SN): a Wave API key belongs to one country account. Gambia (GMD) and Uganda (UGX) are
left out until someone tests them.

### No timestamp window on callbacks
Yoon stores callbacks and verifies them later, so a freshness window would reject delayed
processing. A replayed callback changes nothing: the status API decides.

## Consequences
- A `reversed` payout maps to "no answer": the money came back outside Yoon and a human must look
  (the payout ends up in review).
- Partial refunds are refused (`partial_refund_not_supported`); refund the rest with a payout.
- Webhooks are configured in the Wave Business portal, not per request (runbook).
- Built from the public documentation, not yet run against a Wave Business account.
