# Changelog

All notable changes. Versions follow [semantic versioning](https://semver.org); `/v1` of the API
never changes incompatibly.

## [Unreleased]

### Providers
- New provider **Wave (direct)** (`wave`, Wave Business API): checkout sessions, payouts with
  idempotency keys, full-amount refunds, signed webhooks; SN, CI, ML, BF in XOF. Same method
  `wave` as the aggregators, so routing fails over between them. Not yet run against a Wave
  Business account. See `docs/providers/wave.md` and ADR-0020.
- New provider **PI-SPI** (`pispi`, BCEAO instant payments, via the merchant's institution's API
  Business): payment requests to a customer's PI alias, payouts to a PI alias, full-amount
  refunds (returns of funds), OAuth2 client credentials and mutual TLS. Not yet run against the
  PI-SPI sandbox. See `docs/providers/pispi.md` and ADR-0019.
- `ProviderHttp` supports mutual TLS (client certificate from PEM), `PUT` and form posts.
- DexPay and PayDunya refuse a payout without a phone number (`phone_required`).

### API
- `customer.pi_alias` on `POST /v1/payments` and `recipient.pi_alias` on `POST /v1/payouts`.
  `recipient.phone` is now optional (one of the two is required).
- **Upgrade note:** in payout responses `recipient.phone` is `null` for a payout sent to a PI
  alias. Payouts sent to a phone are unchanged.
- Migration `V5__pi_alias`: alias columns; `payouts.recipient_phone` becomes nullable.

### Clients
- Symfony bundle in `yoonpay/yoon-php` (`Yoon\Symfony\YoonBundle`): `config/packages/yoon.yaml`, an
  autowired `Yoon`, and `#[YoonWebhook]` on webhook controllers (signature check, duplicates
  answered without calling the controller through Symfony Cache, an event remembered only after a
  2xx answer), with `Yoon\Webhook\Event` injected as an argument. Symfony 6.4 LTS and 7.x.
- Python client `yoonpay` (`clients/python`): `create_payment`, `get_payment`, `refund`,
  `create_payout`, `export_csv` with an explicit idempotency key, no retries, `YoonException` with
  Yoon's `code`, webhook verification, and webhook helpers for Django, FastAPI and Flask.
  Python 3.10+.
- Spring Boot starter `io.github.crossben:yoon-spring-boot-starter`
  (`clients/java-spring-boot-starter`): a `Yoon` bean from `yoon.url` / `yoon.api-key` and a
  webhook filter on `yoon.webhook.paths`, with `YoonEvent` injectable into controllers. Spring
  Boot 3.x and 4.x.
- New JavaScript/TypeScript client `@yoonpay/yoon` (`clients/js`): generated from the API
  contract, Node ≥ 20 (also Bun, Deno and edge runtimes), with idempotency-key-first helpers,
  `YoonException`, webhook signature verification and adapters for Express, Fastify, NestJS and
  Next.js (plus a framework-free `verifyWebhook` for Web `Request`). Shared signature test
  vector, e2e scenario (`clients/e2e/scenario.md`) and `clients-e2e` CI job.

## [0.1.0] - 2026-09-30

First release.

### Gateway
- One API for collections, full and partial refunds, and payouts; routing by capability,
  availability and priority; failover only after a definite refusal, never after a timeout;
  payouts never retried or failed over.
- Idempotency on every write; problem+json errors with stable codes; cursor pagination; status
  history; balances, ledger entries and CSV exports; per-application API keys.
- Shadow double-entry ledger whose balance is enforced by Postgres.
- Provider callbacks stored, verified and re-confirmed with the provider's status API; signed,
  retried events to applications with dead letters; automatic reconciliation.
- Operator API (`/admin/v1`), operator CLI (`apps create | list | add-key | revoke-key`).

### Providers
- PayDunya (collect, payout), DexPay (collect, payout), NabooPay (collect), and a demo provider.

### Clients
- `yoonpay/yoon-php` (PHP 8.2+, Laravel 10–13) and `io.github.crossben:yoon-java` (Java 17+), generated
  from the API contract.

### Operations
- Docker image with healthcheck (amd64, arm64), production Compose with Caddy, backups,
  Prometheus alert rules and a Grafana dashboard; runbook; published load-test numbers.

### Known limitations
- The provider adapters are tested against simulated APIs built from production integrations,
  not against the providers' sandboxes.
- Senegal (XOF) only. No provider offers refunds through an API; refund by payout.
- Provider settings are read at startup (restart to apply changes).
