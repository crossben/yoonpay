# Changelog

All notable changes. Versions follow [semantic versioning](https://semver.org); `/v1` of the API
never changes incompatibly.

## [Unreleased]

## [0.1.0] - 2026-10-01

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
- A customer or payout recipient can be identified by phone or by PI-SPI payment alias
  (`customer.pi_alias`, `recipient.pi_alias`).

### Providers
- PayDunya (collect, payout), DexPay (collect, payout), NabooPay (collect), and a demo provider.
- **Wave (direct)** (`wave`, Wave Business API): checkout sessions, payouts with idempotency
  keys, full-amount refunds, signed webhooks; SN, CI, ML, BF in XOF. Same method `wave` as the
  aggregators, so routing fails over between them. See `docs/providers/wave.md`, ADR-0020.
- **PI-SPI** (`pispi`, BCEAO instant payments, through the merchant's institution's API
  Business): payment requests to a customer's PI alias, payouts to a PI alias, full-amount
  refunds (returns of funds), OAuth2 client credentials and mutual TLS; the eight UEMOA
  countries in XOF. See `docs/providers/pispi.md`, ADR-0019.

### Clients
- PHP `yoonpay/yoon-php` (PHP 8.2+): Laravel 10–13 (facade, `yoon.webhook` middleware) and a
  Symfony bundle (`Yoon\Symfony\YoonBundle`, `#[YoonWebhook]`; Symfony 6.4 LTS and 7.x).
- Java `io.github.crossben:yoon-java` (Java 17+) and a Spring Boot starter
  `io.github.crossben:yoon-spring-boot-starter` (Spring Boot 3.x and 4.x).
- JavaScript/TypeScript `@yoonpay/yoon` (Node ≥ 20, also Bun, Deno and edge runtimes), with
  adapters for Express, Fastify, NestJS and Next.js.
- Python `yoonpay` (Python 3.10+), with webhook helpers for Django, FastAPI and Flask.
- All generated from the API contract with a thin hand-written layer: an explicit idempotency
  key on every write, no retries, one exception type carrying Yoon's error `code`, and webhook
  signature verification checked against a shared test vector.

### Operations
- Docker image with healthcheck (amd64, arm64), production Compose with Caddy, backups,
  Prometheus alert rules and a Grafana dashboard; runbook; published load-test numbers.

### Known limitations
- The provider adapters are tested against simulated APIs, not against the providers'
  sandboxes: PayDunya, DexPay and NabooPay from production integrations, Wave from its public
  documentation, PI-SPI from the BCEAO specification. Test with your own sandbox keys before
  taking real payments.
- PayDunya, DexPay and NabooPay: Senegal (XOF) only and no refund API (refund by payout). Wave
  and PI-SPI refund the full amount only; send a partial refund as a payout.
- Provider settings are read at startup (restart to apply changes).
