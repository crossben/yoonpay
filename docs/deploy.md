# Deploying Yoon on a VPS

One small Linux server runs Yoon, Postgres, HTTPS (Caddy) and nightly backups. Everything is in
[`deploy/`](../deploy).

## You need

- A Linux VPS with Docker (2 vCPU / 2 GB RAM is enough to start; see [load test](load-test.md)).
- A domain name pointing at it (an `A` record), e.g. `pay.example.com`.
- Ports 80 and 443 open. Nothing else needs to be public.

## Install

```sh
git clone https://github.com/crossben/yoonpay.git && cd yoonpay/deploy
cp .env.example .env
```

Edit `.env`:

- `YOON_DOMAIN` and `YOON_PUBLIC_URL` — your domain. Providers call back to
  `https://<domain>/v1/hooks/…`, so it must be reachable over HTTPS.
- `POSTGRES_PASSWORD`, `YOON_ADMIN_TOKEN` — long random values (`openssl rand -hex 32`).
- `YOON_IMAGE` — pin a version, e.g. `ghcr.io/crossben/yoon:0.1`.

```sh
docker compose up -d
docker compose logs -f yoon           # wait for "Started YoonServerApplication"
curl https://pay.example.com/v1/about
```

Caddy obtains and renews the TLS certificate by itself.

## Add an application

```sh
docker compose run --rm yoon apps create shop
```

It prints the API key **once** and the callback URL to give your providers. Then add the
application's settings to `.env` (providers, webhook URL and secret — see `.env.example` and
[docs/providers](providers/)), and apply them:

```sh
docker compose up -d        # recreates Yoon with the new settings
```

## Monitoring (optional)

```sh
docker compose --profile monitoring up -d
```

Prometheus (with Yoon's alert rules) and Grafana (with the Yoon dashboard) listen on
`127.0.0.1:9090` and `127.0.0.1:3000` only. Reach them through an SSH tunnel:
`ssh -L 3000:localhost:3000 you@server`. Set `GRAFANA_ADMIN_PASSWORD`, or change the default at
first login. Metrics are never exposed through Caddy.

## Backups

The `backup` service writes `deploy/backups/yoon-<timestamp>.dump` every 24 hours and keeps 14
days. **Copy them off the server** (rsync, object storage): a backup on the same disk is not a
backup. Restoring is in the [runbook](runbook.md#restore-a-backup).

## Next

Read the [runbook](runbook.md) before you need it, and the [security checklist](security-checklist.md)
before going live.
