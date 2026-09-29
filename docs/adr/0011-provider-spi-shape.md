# ADR-0011 — Provider SPI: per-application instances, status per operation

- Status: accepted
- Date: 2026-09-29

## Decision
- A provider module exposes a `PaymentProviderFactory` that builds a
  `PaymentProvider` from one application's credentials. The server keeps one
  instance per (application, provider). Credentials come from env vars
  (`YOON_APPS_<APP>_PROVIDERS_<PROVIDER>_CREDENTIALS_<KEY>`); adding an
  application's provider requires a restart in v1.
- The SPI has one status query per operation — `status`, `refundStatus`,
  `payoutStatus` — each returning the already-mapped status, the raw provider
  value and the confirmed amount. There is no separate `map(rawStatus)` method;
  each adapter keeps its mapping in one class with a table test.

## Why
Every application brings its own merchant keys, so a provider cannot be a
singleton. Unknown refunds and payouts need their own status queries for the
sweep to resolve them.
