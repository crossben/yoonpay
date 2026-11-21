# Plan — money-safety fixes (November 2026)

Source: the 2026-11-19 audit of main @ `f56db29` (0.7.0) and PR #1 by @mooder1. Every finding was
re-checked against the code before this plan; all hold. Status: **accepted 2026-11-21** (D1: reserved until an operator releases it; D2: no re-send,
human review).

## Findings, re-checked

| Id | Finding | Real exposure |
| --- | --- | --- |
| C1 | A refund settled FAILED frees its amount; if the provider later confirms it (FAILED → REFUNDED is APPLY), a second refund can already exist: refunded total > payment. | Money: a double refund. Needs a stored `provider_reference` (failed by the status API, not by a rejected call). |
| H1 | `settleRefund` / `settlePayout` ignore the provider's confirmed amount; `settlePayment` checks it. | Ledger disagrees with what moved. |
| H2 | A crash between the provider call and the status move leaves a refund or payout CREATED forever: no sweep selects CREATED, webhooks can't match (no reference), CREATED → REFUNDED/PAID is illegal. | Payout: money left, never reserved or posted. Refund: money left, amount stuck. |
| M1 | A payment whose confirmed amount mismatches is re-selected every 30 s forever, a new alert and status event each time; 100 of them fill the sweep batch and starve every other open payment. | Operations; real money left unapplied. |
| M2 | PayDunya, NabooPay, Stripe collects that end Unknown with no reference can never be matched later (no `lookup` for COLLECT; NabooPay sends no Yoon id at all). | Low: the customer never got the checkout URL. A paid one would never be credited. |
| M3 | PI-SPI, Wave, Stripe refunds: any failed read (401, 5xx, timeout) becomes `PAYMENT_NOT_FOUND`, a terminal FAILED. | Misreported cause; no money moves (nothing mutating was sent). |
| M4 | Stripe refund lookup reads only the 100 most recent refunds. | A lost refund older than 100 others stays UNKNOWN. |
| M5 | Wave refund status check re-POSTs the refund, relying on Wave's idempotency. | A status check could itself move money. |
| M6 | PayDunya IPN "signature" is `sha512(master key)`, a constant. | Bounded: callbacks only trigger a status-API check on an existing record. |

Not fixable here without the provider: PI-SPI `txId` on refunds, JWS, bulk/cancel (audit "known gaps").

## Milestones (each: tests + docs + CHANGELOG, then stop for review/commit)

1. **C1 + H1** — PR #1's fix carried forward (co-author @mooder1) with tests: failed-with-reference
   refunds stay committed (per D1); `late_refund` and `refund_exceeds_payment` alerts;
   amount guard on refund and payout settlement, with a per-adapter test pinning which field each
   `payoutStatus`/`refundStatus` reports.
2. **H2** — a recovery sweep for refunds and payouts left CREATED longer than a grace period:
   `lookup` by Yoon id where the adapter supports it → UNKNOWN with the reference, settled as usual;
   otherwise UNKNOWN + `needs_review` alert. Never re-sent.
3. **M1** — a mismatched payment is parked once: flagged for review, one alert, out of the sweep
   until an operator resolves it.
4. **Adapters** — M3 (a failed read is `PROVIDER_UNAVAILABLE`, not `PAYMENT_NOT_FOUND`), M4 (filter
   Stripe refunds by payment intent), M5 (per D2), M2 (send a Yoon id where the provider takes one;
   document the rest), M6 (documented in `docs/providers/paydunya.md`; nothing stronger exists).

## Progress
- M1 C1 + H1 — done: failed-with-reference refunds hold their amount until released (`V11`,
  admin `release`/`resolve`, `refunds` CLI); `late_refund`, `refund_exceeds_payment`; refunds and
  payouts with a mismatched amount flagged `needs_review` once. `RefundSafetyTest` (7); the C1
  test fails against the old rule (checked). 282 server tests.
- M2 H2 — done: a refund or payout still CREATED after `YOON_SWEEP_INTERRUPTED_AFTER` (5 min)
  becomes UNKNOWN (payout reserved), never re-sent; looked up by Yoon's id where the adapter can;
  refunds unknown past `YOON_SWEEP_REFUND_REVIEW_AFTER` flagged once; flagged rows swept last.
  `InterruptedRecoveryTest` (4); the recovery test fails with the recovery off (checked).

## Decisions

- **D1 — refunds that really failed after the provider accepted them (decided 2026-11-21):** the
  amount stays committed until an operator releases it (admin API + CLI) after checking with the
  provider. A late success after release still applies (money moved) and raises
  `refund_exceeds_payment` if the total goes over.
- **D2 — Wave refund status (decided 2026-11-21):** the status check never re-sends the refund. A
  Wave refund whose outcome is unknown is flagged for human review; the operator checks the Wave
  dashboard and resolves it.
