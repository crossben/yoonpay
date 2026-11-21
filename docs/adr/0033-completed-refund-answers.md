# ADR-0033 — A provider answer that already confirms a refund

- Status: accepted (2026-11-21)
- Date: 2026-11-21
- Builds on: ADR-0010 (three outcomes), money-safety plan 2026-11 (decision D2)

## Context
Wave has no refund status API. Its refund call (`POST /v1/checkout/sessions/{id}/refund`)
"returns an empty body and an HTTP 200 Success code when the payment is refunded", and refunding
twice "will simply return HTTP 200 Success again" without a second transaction (docs.wave.com,
Checkout API, read 2026-11-21). Until now the adapter answered a 200 with `Accepted`, Yoon marked the
refund PENDING, and the status check re-sent the refund to learn its outcome: a status check that
could move money. Decision D2 (2026-11-21): a status check never re-sends.

## Decision
`CallOutcome.Accepted` gains `completed` (false by default; the three-argument constructor is
unchanged). An adapter returns `Accepted.completed(reference)` when the provider's answer to the
mutating call is itself the confirmation. `RefundService` then records PENDING and REFUNDED at once,
both through `RefundTransitions` (state machine, ledger, events) — no status query.

Only refunds honour it: payments and payouts always wait for the status API or a callback.

Wave's `refundStatus` no longer calls Wave: it reports no answer. A Wave refund whose call was lost
(timeout, 5xx) stays UNKNOWN and is flagged for review after `YOON_SWEEP_REFUND_REVIEW_AFTER`; an
operator checks the Wave dashboard and resolves it.

## Consequences
- Normal Wave refunds are REFUNDED as soon as Wave answers, with no second call.
- No Yoon code path re-sends a refund.
- Lost Wave refund answers need a human (rare: the call usually answers).
