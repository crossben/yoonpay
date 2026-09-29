# ADR-0012 — Webhooks in, events out, reconciliation

- Status: accepted
- Date: 2026-09-29

## Decision

**One way to change a status.** `PaymentTransitions`, `RefundTransitions` and
`PayoutTransitions` are the only code that changes a status. In one transaction
they lock the row, ask the state machine, append to `status_events`, apply,
post to the ledger and write the outbound event. The API, webhooks, sweeps and
the admin API all go through them.

**Provider callbacks are hints.** `POST /v1/hooks/{provider}/{application}`
stores the raw body and answers 200. A worker verifies the signature, finds the
record by provider reference *within that application*, then asks the
provider's status API using the reference Yoon stored. Nothing in a callback
body is trusted. A success is applied only if the confirmed amount equals the
requested amount; otherwise the payment stays pending and an alert is raised.

**One settle function, two callers** (`Settlement`): the webhook worker
(primary) and the sweep (fallback). When the answer to a create call was lost,
`PaymentProvider.lookup` recovers the provider's reference from Yoon's attempt
reference.

**Sweeps** (`Reconciler`, one node at a time via ShedLock):
- open payments, refunds and payouts older than `YOON_SWEEP_PENDING_AFTER` are
  queried;
- a payment still unpaid after `YOON_SWEEP_PAYMENT_TTL`, or unanswerable for
  `YOON_SWEEP_MAX_NO_ANSWER` checks past it, becomes EXPIRED;
- failed/expired payments are re-checked once, later, to catch late successes;
- a payment left in CREATED by a crash becomes PENDING (a call may have left) or
  FAILED (none did);
- a payout still open after `YOON_SWEEP_PAYOUT_REVIEW_AFTER` is flagged
  `needs_review` for a human; payouts are never resolved by timeout.

**Events out** go through a transactional outbox. A worker claims rows with a
lease (`FOR UPDATE SKIP LOCKED`), POSTs them signed
(`Yoon-Signature: t=…,v1=HMAC-SHA256(secret, "t.body")`, 5-minute tolerance),
retries with exponential backoff (30 s doubling, capped at 6 h) and marks them
DEAD after 12 attempts. Delivery is at-least-once and unordered; events carry a
unique id.

## Why
Callbacks can be replayed (PayDunya's hash is constant), forged, duplicated,
late or missing. Only the provider's status API is authoritative, and only a
single, idempotent settle path keeps webhook and sweep from disagreeing.
