<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/logo-dark.svg">
    <img src="docs/assets/logo.svg" alt="Yoon" width="280">
  </picture>
</p>

# Yoon

> *Yoon* (Wolof): the way, the road. Yoon picks the way a payment travels.

**A self-hosted, open-source payment gateway for Africa's payment providers.**
One API in front of PayDunya, DexPay, NabooPay, Wave and PI-SPI (more later): routing,
verified webhooks, idempotency, a ledger and automatic reconciliation — written
once, in Java, instead of in every project.

> **Status: 0.1.0 — first release.** The gateway, its operations tooling, its PayDunya,
> DexPay, NabooPay, Wave (direct) and PI-SPI adapters and its clients are built and tested. The
> adapters are tested against simulated provider APIs (built from production integrations;
> Wave: from its public documentation; PI-SPI: from the BCEAO specification); they have
> **not yet been run against the providers' sandboxes**. Try it with the demo provider, and
> test with your own sandbox keys before taking real payments.

## What works today

- **Money** as integer minor units per currency (5 000 XOF is `5000`). No
  floating point anywhere.
- **State machines** for payments, payouts and refunds. Every status change is
  checked: a late "failed" after a success is ignored; a confirmed success after
  an expiry is recovered; an illegal change is an error, never a silent overwrite.
- **Three outcomes per provider call** — accepted, rejected, unknown. A timeout
  is *unknown*, never *failed*, so Yoon will not fail over or retry a payment the
  provider may already be processing (no double charge, no double payout).
- **Shadow ledger** (double entry) in Postgres. The database itself refuses an
  unbalanced posting and any edit or deletion — tested against a real Postgres.
- **Idempotency store**: the same request sent twice, or twenty times at once,
  runs exactly once.
- **FakeProvider** (`yoon-testkit`): a scriptable provider that accepts, rejects,
  hangs, goes down, times out after accepting, reports partial payments and sends
  duplicate, late or forged webhooks.
- **HTTP API** with per-application API keys, documented at `/docs`:
  - payments, full and partial refunds, payouts;
  - **routing**: you send intent (amount, country, method); Yoon picks the
    configured provider by capability, availability and priority, or the one you
    pin;
  - **failover only after a definite refusal** — never after a timeout — and
    never for payouts;
  - a circuit breaker per provider takes a failing provider out of routing;
  - search, cursor pagination, status history of every payment/refund/payout,
    balances and ledger entries, CSV exports for accounting;
  - errors as `application/problem+json` with stable codes.
- **Documentation that cannot drift**: tests fail if a route is missing from
  `api/openapi.yaml` or if any response does not match it.
- **Provider callbacks treated as hints**: stored raw, signature checked, then
  **re-confirmed with the provider's status API** before anything changes.
  Tested harmless: replayed, duplicated, late, forged, lying and cross-application
  callbacks. A success is applied only if the confirmed amount matches.
- **Events to your application** (`payment.succeeded`, `payout.paid`, …): written
  in the same transaction as the change, signed (HMAC-SHA256 with timestamp),
  retried with backoff, then dead-lettered and replayable. Also listed at
  `GET /v1/events` for catching up.
- **Automatic reconciliation**: stuck payments, refunds and payouts are resolved
  through the provider's status API without human action; unpaid payments expire;
  a success that arrives after expiry is still recovered; a payout whose outcome
  stays unknown is flagged for a human instead of guessed.
- **Operator API** (`/admin/v1`, behind `YOON_ADMIN_TOKEN`): dead letters and
  payouts needing review, resolved through the same state machine.
- **Alerts** as a Prometheus counter `yoon_alerts_total{type=…}` (amount
  mismatch, late success, payout needs review, dead event…).

## Providers

| Provider | Collect (Senegal, XOF) | Payout | Refund | Details |
| --- | --- | --- | --- | --- |
| PayDunya | Wave, Orange Money, Free Money, card | Wave, Orange Money, Free Money | — | [docs/providers/paydunya.md](docs/providers/paydunya.md) |
| DexPay | Wave, Orange Money, Free Money, card | Wave, Orange Money | — | [docs/providers/dexpay.md](docs/providers/dexpay.md) |
| NabooPay | Wave, Orange Money, Free Money, card | — | — | [docs/providers/naboopay.md](docs/providers/naboopay.md) |
| Wave (direct) | Wave, 4 XOF countries | Wave | Full amount | [docs/providers/wave.md](docs/providers/wave.md) |
| PI-SPI (BCEAO) | Payment request to a PI alias, 8 UEMOA countries | To a PI alias | Full amount | [docs/providers/pispi.md](docs/providers/pispi.md) |

PayDunya, DexPay and NabooPay offer no refund API: refund a customer by sending a payout.
Wave (direct) and PI-SPI return the full amount of a payment; send a partial refund as a payout.
Each page lists the credentials to set, how the provider's statuses map to
Yoon's, and the provider's quirks.

## Client libraries

| | Package | |
| --- | --- | --- |
| PHP / Laravel | `yoonpay/yoon-php` | [clients/php](clients/php) — facade, `yoon.webhook` middleware; Laravel 10–13 |
| Java | `io.github.crossben:yoon-java` | [clients/java](clients/java) — Java 17+ |
| JavaScript / TypeScript | `@yoonpay/yoon` | [clients/js](clients/js) — Node ≥ 20, also Bun, Deno and edge; Express, Fastify, NestJS, Next.js adapters |
| Symfony | `yoonpay/yoon-php` | [clients/php](clients/php#symfony) — `YoonBundle`, autowired client, `#[YoonWebhook]`; Symfony 6.4 LTS and 7.x |
| Python | `yoonpay` | [clients/python](clients/python) — Python ≥ 3.10; Django, FastAPI and Flask webhook helpers |
| Spring Boot | `io.github.crossben:yoon-spring-boot-starter` | [clients/java-spring-boot-starter](clients/java-spring-boot-starter) — auto-configured `Yoon` bean and webhook filter; Spring Boot 3.x and 4.x |

The PHP, Java, JavaScript and Python clients are generated from `api/openapi.yaml` (never edited by
hand; CI fails if they drift); the Symfony bundle and the Spring Boot starter build on the PHP and
Java clients. Each has a thin hand-written layer: idempotency-key-first helpers, one exception type carrying Yoon's
error code, and webhook signature verification. Apache-2.0.

## Try it without a provider account

`YOON_DEMO_ENABLED=true` adds a **demo provider**: its checkout page is served by Yoon, you click
Pay or Decline, and a signed callback travels the real pipeline. No money moves. The
[example Laravel shop](examples/laravel-shop) uses it to run end to end.

## Running it in production

- **[Deploy on a VPS](docs/deploy.md)** — `deploy/` has a Compose file with Postgres, Caddy
  (automatic HTTPS), nightly backups, and an optional Prometheus + Grafana profile with alert
  rules and a dashboard.
- **[Runbook](docs/runbook.md)** — upgrades, key rotation, payouts needing review, dead letters,
  restoring a backup.
- **[Security checklist](docs/security-checklist.md)** — before handling real money.
- **Image:** `ghcr.io/crossben/yoon` and on Docker Hub (amd64, arm64), signed with
  cosign, with an SBOM, published by the release workflow on every `v*` tag.

**Load test** ([details](docs/load-test.md)): one instance on a laptop (i7-11800H, Docker
Desktop), with the in-memory demo provider so the numbers measure Yoon itself, sustained

| Checkouts/s (create + read) | Errors | Create p95 | Read p95 |
| --- | --- | --- | --- |
| 100 | 0 % | 36 ms | 3 ms |
| 200 | 0 % | 37 ms | 3 ms |
| 300 | 0 % | 42 ms | 4 ms |

Real providers add their own latency to each create.

## What Yoon is not

- **Not an aggregator.** You open your own merchant account with each provider
  and bring your own keys. Yoon removes the integration work, not the onboarding.
- **Not a card vault.** Yoon never sees card numbers; cards go through the
  provider's hosted checkout.
- **Not a checkout UI.** Yoon returns the provider's checkout URL or push/USSD
  instruction; your app renders its own UI.
- **Not a hosted service.** You run it yourself. It sends no telemetry.

## Run locally

Requirements: Docker. To build from source: JDK 25.

```sh
cp .env.example .env                               # set POSTGRES_PASSWORD
docker compose up --build -d
docker compose run --rm yoon apps create shop      # prints the API key once
```

Then:

- API documentation (Swagger UI): <http://localhost:8080/docs>
- Health: `curl localhost:8080/actuator/health`
- A first call:

```sh
curl -X POST localhost:8080/v1/payments \
  -H "Authorization: Bearer yk_…" \
  -H "Idempotency-Key: $(uuidgen)" \
  -H "Content-Type: application/json" \
  -d '{"amount":5000,"currency":"XOF","country":"SN","method":"wave","customer":{"phone":"+221771234567"}}'
```

Until a provider is configured for the application this answers `422
no_provider_for_method` — that is the routing working.

Build and test (needs a running Docker daemon — integration tests start a real Postgres):

```sh
./mvnw verify
```

## Configuration

| Variable | Required | Meaning |
| --- | --- | --- |
| `YOON_DB_URL` | yes | JDBC URL, e.g. `jdbc:postgresql://localhost:5432/yoon` |
| `YOON_DB_USER` | yes | Database user |
| `YOON_DB_PASSWORD` | yes | Database password |
| `YOON_PUBLIC_URL` | for webhooks | Public HTTPS address of this instance; providers call back to `<url>/v1/hooks/<provider>/<application id>` |
| `YOON_APPS_<APP>_WEBHOOK_URL` | optional | Where Yoon POSTs this application's events. Without it, events are only listed at `GET /v1/events` |
| `YOON_APPS_<APP>_WEBHOOK_SECRET` | with the URL | At least 32 characters; your app verifies `Yoon-Signature` with it |
| `YOON_ADMIN_TOKEN` | optional | Enables the operator API `/admin/v1` (at least 32 characters) |
| `YOON_SWEEP_PAYMENT_TTL` | no | Unpaid payments expire after this (default `30m`) |
| `YOON_SWEEP_PAYOUT_REVIEW_AFTER` | no | Open payouts are flagged for review after this (default `24h`) |
| `YOON_SWEEP_PENDING_AFTER` | no | Wait before the sweep asks a provider (default `1m`) |
| `YOON_DEMO_ENABLED` | no | `true` enables the demo provider and its checkout page. Never in production |
| `YOON_DB_POOL_SIZE` | no | Database connections per instance (default `20`); keep instances × pool below Postgres' `max_connections` |
| `YOON_SOURCE_URL` | if modified | Where users get this instance's source code (AGPL-3.0). Defaults to the upstream repository; set it if you run a modified version |
| `YOON_APPS_<APP>_PROVIDERS_<PROVIDER>_PRIORITY` | per provider | Enables a provider for an application; lower is preferred (default 100) |
| `YOON_APPS_<APP>_PROVIDERS_<PROVIDER>_CREDENTIALS_<KEY>` | per provider | That application's merchant credentials for the provider. Never logged |

`<APP>` is the application name given to `apps create`, upper-cased with `-`
turned into `_`. Provider and webhook settings are read at startup: restart after
changes.

### Verifying Yoon's webhooks in your app

Every delivery carries `Yoon-Signature: t=<unix seconds>,v1=<hex>`. Compute
`HMAC-SHA256(secret, "<t>.<raw body>")` over the **raw** body, compare it in
constant time with `v1`, and reject if `t` is more than 5 minutes old. Delivery
is at-least-once and unordered: deduplicate on the event `id`. The full
description is in the `webhooks` section of `api/openapi.yaml` (and at `/docs`).

### Operator command line

```sh
# from the repository root (or deploy/ in production)
docker compose run --rm yoon apps create <name>      # new application + API key (shown once)
docker compose run --rm yoon apps list               # applications and key prefixes
docker compose run --rm yoon apps add-key <name>     # rotate: issue another key
docker compose run --rm yoon apps revoke-key <prefix>
```

## Contributing and security

[CONTRIBUTING.md](CONTRIBUTING.md) (a CLA is required) · [SECURITY.md](SECURITY.md) (report
vulnerabilities privately) · [CHANGELOG.md](CHANGELOG.md) · [ADOPTERS.md](ADOPTERS.md)

## Licence

Server, core and providers: [AGPL-3.0](LICENSE). Client libraries and examples: [Apache-2.0](clients/LICENSE).
A commercial licence is available for companies that cannot use AGPL. What the AGPL asks of you,
plainly: [docs/licensing.md](docs/licensing.md). The name is covered by [TRADEMARKS.md](TRADEMARKS.md).

## Project layout

| Module | Contents |
| --- | --- |
| `yoon-core` | Domain: money, state machines, ledger model, provider interface. Pure Java, no dependencies. |
| `yoon-testkit` | `FakeProvider` and test helpers. |
| `yoon-providers/` | One module per provider (PayDunya, DexPay, NabooPay, Wave, PI-SPI, demo) plus shared HTTP support (incl. mutual TLS). No Spring. |
| `yoon-server` | The Spring Boot gateway: HTTP API, provider callbacks, outbound events, reconciliation. |
| `api/openapi.yaml` | The API contract, written by hand; served at `/openapi.yaml` and rendered at `/docs`. |
| `clients/` | PHP (Laravel, Symfony), Java (+ Spring Boot starter), JavaScript and Python clients (Apache-2.0); generated parts by `clients/generate.sh`. |
| `examples/laravel-shop` | A Laravel shop paying through Yoon. |
| `docs/adr/` | Architecture decisions and why they were made. |
| `deploy/` | Production Compose, Caddy, Prometheus rules, Grafana dashboard. |
| `load/k6/` | The load-test script. |

Contributors and AI agents: read [CLAUDE.md](CLAUDE.md).
