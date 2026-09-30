# ADR-0016 — Operations and release

- Status: accepted
- Date: 2026-09-30

## Decision
- **Deployment:** one Compose file (`deploy/`) — Yoon, Postgres, Caddy for automatic HTTPS,
  a nightly `pg_dump` service (14 days), and an opt-in `monitoring` profile (Prometheus with
  alert rules, Grafana with a provisioned dashboard) bound to localhost. Metrics are never
  served through Caddy.
- **Health:** the image's `HEALTHCHECK` probes `/actuator/health/readiness` with bash's
  `/dev/tcp` (the JRE image has no curl), so `compose up --wait` means ready.
- **Database pool:** default 20 (`YOON_DB_POOL_SIZE`). The load test showed Hikari's default
  of 10 capping one instance near 240 checkouts/s with idle CPU.
- **Business metrics** (`yoon_status_changes_total`, `yoon_provider_calls_seconds`, outbox and
  review gauges, `yoon_alerts_total`) back the alert rules; `MetricsTest` pins their names.
- **Operator CLI:** `apps create | list | add-key | revoke-key`, so keys rotate without
  downtime.
- **Release:** a `v*` tag builds, tests, and pushes a multi-arch image (amd64, arm64) to GHCR
  and Docker Hub with SBOM and provenance attestations, signs it with cosign (keyless), and
  creates the GitHub release from `CHANGELOG.md` with a CycloneDX SBOM.
- **Contract stability:** CI fails on any breaking or warning-level change to
  `api/openapi.yaml` since the last release tag (oasdiff), besides the client drift check.

## Not decided here
Publishing the PHP client to Packagist (needs a repository with `composer.json` at its root,
e.g. a subtree split of `clients/php`) and the Java client to Maven Central (needs a Central
Portal namespace and signing keys) are owner actions, not yet automated.
