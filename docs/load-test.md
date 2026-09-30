# Load test

What one Yoon instance sustains, measured with [`load/k6/payments.js`](../load/k6/payments.js).
Run on 2026-09-30 against version 0.1.0.

## Method

- **One iteration = one checkout:** `POST /v1/payments` (fresh `Idempotency-Key`, routing,
  provider call, status history, outbound event) then `GET /v1/payments/{id}`.
- **Constant arrival rate** for 2 minutes per run (k6 `constant-arrival-rate`): the load does not
  slow down when Yoon does, so queueing shows up as latency and dropped iterations.
- **Provider:** the in-memory demo provider (`YOON_DEMO_ENABLED=true`). The numbers measure Yoon
  itself — API, Postgres, idempotency, routing, events — **without** a real provider's network
  latency. With a real provider, each create also waits for the provider's answer (typically
  hundreds of milliseconds), which ties up a request thread but not a database connection.
- **Thresholds:** create p95 < 500 ms, read p95 < 200 ms, errors < 1 %.

## Hardware

One laptop, everything on it: Intel Core i7-11800H (8 cores / 16 threads), 32 GB RAM. Yoon,
Postgres 16 and k6 ran in Docker Desktop's VM (16 CPUs, 8 GB), with no resource limits on the
containers, from the repository's `docker-compose.yml`. A VPS will differ: run the script on
your own hardware before relying on these numbers.

## Results

Default configuration (database pool 20 connections):

| Rate (checkouts/s) | Requests | Errors | Create p50 / p95 / p99 | Read p50 / p95 / p99 |
| --- | --- | --- | --- | --- |
| 100 | 24 000 | 0 % | 27 / 36 / 48 ms | 2 / 3 / 5 ms |
| 200 | 48 002 | 0 % | 29 / 37 / 57 ms | 2 / 3 / 6 ms |
| 300 | 71 948 | 0 % | 30 / 42 / 619 ms | 2 / 4 / 135 ms |

The 100 and 200/s runs used the previous default pool (10); at those rates the pool was not the
limit. At 300/s the p99 shows the instance approaching saturation (26 of 36 000 iterations
dropped).

### Where it saturates, and why

| Pool size | Offered 400/s | Achieved | Create p95 |
| --- | --- | --- | --- |
| 10 (Hikari default, before this release) | 400 | **243/s** (18 013 dropped) | 7.2 s |
| 40 | 400 | **399/s** (65 dropped) | 61 ms (p99 829 ms) |

With 10 connections the instance queued on the database pool while its CPU stayed low: each
payment runs several short transactions (idempotency claim, creation, attempt, status change
with its event). The default is now 20 (`YOON_DB_POOL_SIZE`). Keep `instances × pool size`
below Postgres' `max_connections` (100 by default).

### Integrity after the runs

After ~150 000 payments: no idempotency key stuck in progress, every payment has its status
history, and every payment made exactly one provider attempt.

## Reproduce

```sh
cat > .env <<'ENV'
POSTGRES_PASSWORD=local-test
YOON_PUBLIC_URL=http://localhost:8080
YOON_DEMO_ENABLED=true
YOON_APPS_LOAD_PROVIDERS_DEMO_PRIORITY=1
ENV
docker compose up -d --build --wait
KEY=$(docker compose run --rm -T yoon apps create load | grep -o 'yk_[A-Za-z0-9_-]*')
docker compose restart yoon && docker compose up -d --wait
docker run --rm -i --add-host=host.docker.internal:host-gateway \
  -e BASE_URL=http://host.docker.internal:8080 -e API_KEY=$KEY -e RATE=200 -e DURATION=2m \
  grafana/k6:1.3.0 run - < load/k6/payments.js
```
