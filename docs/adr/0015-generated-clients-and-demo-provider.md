# ADR-0015 — Generated clients with a thin hand-written layer; demo provider

- Status: accepted
- Date: 2026-09-30

## Decision

**Clients** (`clients/php`, `clients/java`, Apache-2.0) are generated from
`api/openapi.yaml` by `clients/generate.sh` (openapi-generator 7.16.0 in Docker)
into `generated/`, which is never edited by hand. A small hand-written layer
adds idempotency-key-first helpers, one exception type carrying Yoon's problem
`code`, webhook signature verification and, for PHP, a Laravel service
provider, facade and webhook middleware.

- Only application-facing tags are generated: no admin API, no provider
  callbacks, no webhook descriptions.
- `generate.sh` rewrites the generated PHP's calls to `GuzzleHttp\Utils::jsonEncode`
  (removed in Guzzle 8) to `Yoon\Internal\Json::encode`, so the package supports
  Guzzle 7 and 8 (Laravel 10–13). The script fails if an unpatched helper remains.
- The generated Java exports API cannot read `text/csv`; the Java layer exposes
  `exportCsv` and does not expose the generated exports API.
- CI regenerates and fails on any difference (contract drift); PHP tests run on
  lowest and highest dependencies; `JavaClientTest` runs the Java client against
  the real server; both clients and the server check one shared signature test
  vector (`api/test-vectors/webhook-signature.json`).

**Demo provider** (`yoon-provider-demo`, `YOON_DEMO_ENABLED=true`): an in-memory
provider whose checkout page is served by Yoon (`/demo/checkout/{ref}`). The
visitor's decision is handed to Yoon as a signed callback through the normal
inbound pipeline — verify, re-confirm, settle. It moves no money, logs a warning
at startup, and exists so anyone can try Yoon and run `examples/laravel-shop`
without a provider account.

## Why
Hand-written clients drift from the contract; generated ones alone are awkward
and miss the idempotency discipline. Testing the generated clients against the
real server caught a real generator bug (CSV) that spec validation could not.
