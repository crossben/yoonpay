# Changelog

All notable changes. Versions follow [semantic versioning](https://semver.org); `/v1` of the API
never changes incompatibly.

## [Unreleased]

### Clients
- Every client README has a "Prompt for an AI coding agent": a copy-ready brief that tells a coding
  agent what to install and write, and the rules that protect money (idempotency keys tied to the
  order, fulfil only on `payment.succeeded`, raw-body webhook verification, minor units).

## [0.2.0] - 2026-10-07

Upgrading from 0.1.0: migration `V6` runs on start-up. `/v1` stays compatible: new
fields only, and `method` stays a string (`any` for a hosted checkout not chosen yet).

### API
- `POST /v1/payments` accepts `checkout: "hosted"`: `method` becomes optional, no provider is called,
  and `checkout_url` points to a Yoon-hosted page where the customer picks the method (ADR-0024).
  Until the customer chooses, `method` is `any` (still a string, so 0.1.0 clients keep working).
- Payments gain `checkout` (`direct`/`hosted`) and `checkout_expires_at`.
- New public, token-scoped endpoints for the checkout page: `GET /checkout/api/{id}`,
  `POST /checkout/api/{id}/attempts` (tag "Hosted checkout", not in the client libraries).

### Gateway
- Hosted checkout page at `/checkout/{id}`: static, strict CSP, FR/EN, light/dark, phone width.
  One attempt round at a time (claimed before any provider call); an unknown outcome or an
  accepted attempt ends the choice; refusals let the customer choose again (max 10 attempts).
- Unused hosted checkouts fail with `checkout_expired` after `YOON_CHECKOUT_TTL` (default 30m).
- Public checkout endpoints are rate-limited per client address (`YOON_CHECKOUT_RATE_LIMIT`,
  default 300/min). Yoon now takes the client address from `X-Forwarded-For` sent by private-network
  proxies (`server.forward-headers-strategy=native`).
- Dashboard: responsive — tables become cards on phones and tablets (two per row on tablets), ids
  wrap, tabs scroll in one row; no sideways scrolling from 320 to 1920 px wide.
- Operator dashboard at `/dashboard` (`YOON_DASHBOARD_ENABLED`, default `true`; needs
  `YOON_ADMIN_TOKEN`): applications, payments, refunds, payouts, payouts needing review,
  dead letters with replay, balances, ledger and status history. Static page with a strict CSP.
- Operator API: read-only `GET /admin/v1/applications` and per-application
  `/admin/v1/applications/{application_id}/{payments,refunds,payouts}` (+ `/{id}/events`),
  `/balances` and `/ledger/entries`, same results as the application's own `/v1` routes.

### Providers
- New provider **CinetPay** (`cinetpay`, API v1 as used by CinetPay's 2026 SDKs): mobile-money
  payments and transfers in CI, SN, BF, ML, TG, BJ, NE (XOF), CM (XAF) and GN (GNF), per-country
  credentials; no refund API. Notifications are not trusted (CinetPay does not sign them); the
  reconciliation sweep settles CinetPay transactions. Not yet run against the CinetPay sandbox.
  See `docs/providers/cinetpay.md` and ADR-0023.
- New provider **Stripe** (`stripe`, method `card`): Stripe Checkout for international cards,
  full and partial refunds, idempotency keys on every create, `Stripe-Signature` webhooks; no
  payouts. Currencies limited to those where Stripe's amount unit matches ISO 4217 (XOF, XAF,
  EUR, USD, GBP, CAD, CHF). Not yet run against a Stripe test account. See
  `docs/providers/stripe.md` and ADR-0022.
- Every adapter now passes a shared money-safety suite, `ProviderContract` (`yoon-testkit`):
  refused connection → rejected; 5xx, dropped connection or timeout after sending → unknown;
  no guessed statuses; unoffered operations send nothing; forged callbacks are invalid.

### Clients
- All clients are published: `yoonpay/yoon-php` (Packagist), `io.github.crossben:yoon-java` and
  `io.github.crossben:yoon-spring-boot-starter` (Maven Central), `@yoonpay/yoon` (npm),
  `yoonpay` (PyPI). The JS client is published with npm trusted publishing (no token).
- Regenerated for the new API fields (`checkout`, `checkout_expires_at`, operator read models).

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
