# Changelog

All notable changes. Versions follow [semantic versioning](https://semver.org); `/v1` of the API
never changes incompatibly.

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
