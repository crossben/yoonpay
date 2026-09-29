# ADR-0014 — Provider modules: JDK HTTP client, no Spring

- Status: accepted (refines ADR-0003)
- Date: 2026-09-29

## Decision
Provider modules (`yoon-providers/*`) use the JDK `HttpClient` through
`ProviderHttp` in `yoon-provider-support`, not Spring's `RestClient`. They
depend on `yoon-core`, the support module, Jackson and the JDK only — enforced
by `ProvidersArchitectureTest`. The server registers each module's factory as a
bean.

`ProviderHttp` classifies every call:
- `NotSent` — connection refused, DNS failure, connect timeout: provably never
  reached the provider (safe to fail over);
- `Lost` — read timeout, reset, I/O error mid-exchange: outcome unknown;
- `Answered` — an HTTP response; adapters treat 5xx on mutating calls as unknown.

It never retries and sets connect (5 s) and request (20 s) timeouts.

## Why
The three-outcome rule depends on telling "never sent" from "sent, no answer".
The JDK client reports these as distinct exceptions (`ConnectException`,
`HttpConnectTimeoutException` vs `HttpTimeoutException`); `RestClient` wraps
them all in `ResourceAccessException`. Keeping Spring out also lets provider
modules be reused outside the server.

## Also decided
- `CallOutcome.Unknown` may carry the provider reference when the adapter
  already knows it (DexPay keys on Yoon's reference; PayDunya's disburse token
  comes from the first of two calls). The server stores it, so the status check
  needs no lookup.
- v1 support: PayDunya collect + payout; DexPay collect + payout; NabooPay
  collect only (its cashout has no idempotency key and no callback). No provider
  offers refunds through an API; applications refund by payout.
