# ADR-0003 — Raw HTTP to providers, no provider SDKs

- Status: accepted
- Date: 2026-09-29

## Decision
Each provider module calls the provider with Spring `RestClient` and
hand-written request/response records. No vendor SDK.

## Why
- Yoon needs three outcomes per call — accepted, rejected, **unknown** — to
  prevent double charges and payouts. SDKs collapse rejection and timeout into
  one exception.
- SDKs bring their own HTTP client, timeouts and sometimes hidden retries, which
  bypass Resilience4j. A hidden retry on a payout pays twice.
- SDK webhook helpers parse JSON before verifying; signatures must be checked
  over the raw body bytes.
- All prior-art integrations already use raw HTTP; the provider quirks live in
  that code.
- The APIs are small (3–5 endpoints); ~300 lines per module, fully owned and
  tested with WireMock.
