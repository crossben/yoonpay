# Security checklist

Go through this before a Yoon instance handles real money. Items marked *(built in)* are what
Yoon does by itself; the rest is your deployment.

## Secrets
- [ ] `POSTGRES_PASSWORD`, `YOON_ADMIN_TOKEN` and every webhook secret are long random values
      (`openssl rand -hex 32`), different from each other.
- [ ] `.env` is readable only by the deploying user (`chmod 600 .env`), never committed.
- [ ] Provider keys are live keys only on the production instance.
- *(built in)* Secrets and credentials are never logged; API keys are stored hashed; phone
  numbers are masked in logs, API responses and exports.

## Network
- [ ] Only ports 80 and 443 are open; Postgres, Prometheus and Grafana are not reachable from
      outside (the provided Compose file binds them to localhost or the internal network).
- [ ] `YOON_PUBLIC_URL` is HTTPS.
- [ ] If you don't use the operator API, leave `YOON_ADMIN_TOKEN` empty: it is then disabled.
- *(built in)* Metrics are not served through Caddy; the container runs as a non-root user.

## Money safety
- [ ] `YOON_DEMO_ENABLED` is **not** set in production.
- [ ] Each provider's callback URL points at `https://<domain>/v1/hooks/<provider>/<application id>`.
- [ ] Alerts from `deploy/prometheus/alerts.yml` reach a human (payouts needing review, amount
      mismatches, dead events).
- *(built in)* Callbacks are re-confirmed with the provider before anything changes; timeouts
  never trigger failover or retries; payouts are sent once; amounts must match to settle; the
  ledger balance is enforced by Postgres.

## Applications
- [ ] Each application verifies `Yoon-Signature` over the raw body and rejects stale timestamps
      (the PHP/Laravel middleware and the Java helper do).
- [ ] Each application uses an idempotency key tied to its own order, and deduplicates events on
      their id.
- [ ] Each application has its own API key; keys are rotated when people leave
      ([runbook](runbook.md#rotate-an-applications-api-key)).

## Operations
- [ ] Backups are copied off the server, and a restore has been tested.
- [ ] The image version is pinned (`YOON_IMAGE`), and upgrades follow the runbook.
- [ ] Someone watches this repository's security advisories.
