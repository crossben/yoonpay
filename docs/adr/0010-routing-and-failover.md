# ADR-0010 — Routing, failover and unknown outcomes

- Status: accepted
- Date: 2026-09-29

## Decision
- Applications send intent (amount, country, method, currency); the `Router`
  orders configured providers: capable → circuit not open → optional pin →
  priority → id.
- A mutating provider call returns `Accepted`, `Rejected` or `Unknown`
  (`CallOutcome`). Adapters return `Unknown` for any timeout, 5xx, dropped
  connection or unparseable answer after the request may have left.
- **Payments** fail over to the next provider only after `Rejected`. `Unknown`
  stops routing: the payment stays `PENDING` on that provider until its status
  API answers.
- **Refunds** always go to the provider that collected the payment; never
  retried.
- **Payouts** go to exactly one provider, exactly once — no failover even after
  a rejection, since the recipient and amount are the caller's decision to
  re-issue. `Unknown` payouts become `UNKNOWN`.
- Each (application, provider) has its own circuit breaker (Resilience4j).
  `Unknown` outcomes and "not sent" rejections count as failures; business
  rejections do not. An open breaker removes the provider from routing and
  answers `Rejected(PROVIDER_UNAVAILABLE)` without contacting it.
- An adapter that throws is treated as `Unknown`, never as `Rejected`.

## Why
Retrying or failing over a call that may already have reached the provider
risks charging a customer twice or paying a recipient twice. Neither can be
undone by software.

## Consequences
Some payments stay `pending` longer instead of succeeding elsewhere. The
reconciliation sweep resolves them through the provider's status API.
