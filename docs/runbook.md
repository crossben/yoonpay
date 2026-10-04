# Runbook

Commands run from the `deploy/` directory of a server installed with [deploy.md](deploy.md).
`$ADMIN` is your `YOON_ADMIN_TOKEN`, `$YOON` your public URL.

## Upgrade

```sh
# 1. Read CHANGELOG.md for the target version's upgrade notes.
# 2. Back up first.
docker compose exec backup sh -c 'pg_dump -h postgres -U yoon -d yoon -Fc -f /backups/pre-upgrade.dump'
# 3. Point YOON_IMAGE at the new version in .env, then:
docker compose pull yoon && docker compose up -d
docker compose logs -f yoon     # Flyway applies migrations on start; wait for "Started"
```

Migrations are forward-only and never destructive within a minor version. To roll back, restore
the pre-upgrade backup and the previous image together.

## Rotate an application's API key

```sh
docker compose run --rm yoon apps add-key shop      # new key, shown once; the old one still works
# deploy the new key to the application, then:
docker compose run --rm yoon apps list              # find the old key's prefix
docker compose run --rm yoon apps revoke-key yk_AbCdEfG
```

## Rotate a provider credential or webhook secret

Change the value in `.env`, then `docker compose up -d`. For a webhook secret, update the
application first if it accepts only one secret: deliveries fail (and are retried) until both
sides agree.

## Set up the Wave webhook

In the Wave Business portal (Developers → Webhooks), add a webhook per application:
URL `https://<YOON_PUBLIC_URL>/v1/hooks/wave/<application id>`, security **signing secret**,
events `checkout.session.completed` and `checkout.session.payment_failed`. Copy its secret
(`wave_..._WHS_...`) into `YOON_APPS_<APP>_PROVIDERS_WAVE_CREDENTIALS_WEBHOOK_SECRET` and
`docker compose up -d`. During a rotation Wave signs with both secrets, so update the variable
once the new secret is issued.

## Register the PI-SPI webhook

PI-SPI does not take a callback URL per request: register one webhook per application, once, with
your institution's API Business (with your OAuth2 token, API key and client certificate):

```sh
curl --cert client.crt --key client.key \
  -H "Authorization: Bearer $TOKEN" -H "x-api-key: $API_KEY" -H "Content-Type: application/json" \
  -d '{"callbackUrl":"https://<YOON_PUBLIC_URL>/v1/hooks/pispi/<application id>",
       "events":["RTP_REJETE","PAIEMENT_RECU","PAIEMENT_ENVOYE","PAIEMENT_REJETE",
                 "RETOUR_ENVOYE","RETOUR_REJETE"]}' \
  "$BASE_URL/webhooks"
```

The answer contains a `secret`: set it as `YOON_APPS_<APP>_PROVIDERS_PISPI_CREDENTIALS_WEBHOOK_SECRET`
and `docker compose up -d`. To rotate it, `POST $BASE_URL/webhooks/<id>/secrets` with a
`dateExpiration`, then update the variable. Missed callbacks are harmless: the sweeps ask the
status API anyway.

## Operator dashboard

Open `$YOON/dashboard` and paste the admin token. It shows applications; payments, refunds and
payouts per application (filter by status, newest first); payouts needing review; dead letters
(with **Replay**); balances and ledger entries; and the status history of any record (click its id).
The token stays in that browser tab's session storage; **Sign out** or closing the tab forgets it.
The dashboard does not resolve payouts: use `POST /admin/v1/payouts/{id}/resolve` below.

It exists only with `YOON_ADMIN_TOKEN` set; `YOON_DASHBOARD_ENABLED=false` removes it (404). The
same data is available with curl, e.g.
`curl -H "Authorization: Bearer $ADMIN" "$YOON/admin/v1/applications/<id>/payments?status=pending"`.

## Hosted checkout

Applications that create payments with `"checkout": "hosted"` send their customers to
`$YOON_PUBLIC_URL/checkout/<payment id>?t=<token>` (ADR-0024), so that page must be reachable from
the internet at `YOON_PUBLIC_URL` (without it, hosted payments are refused with
`public_url_required`). The page lists the methods the application's providers support for the
payment's country and currency, starts the provider attempt when the customer chooses, then polls
the status.

- A checkout nobody uses becomes `failed` with failure code `checkout_expired` after
  `YOON_CHECKOUT_TTL` (default `30m`); the payment sweep does it, and the page does it on read.
- After 10 refused attempts a checkout becomes `failed` (`checkout_attempts_exhausted`).
- A choice whose provider answer is unknown leaves the payment `pending` on that provider, as for
  any payment: the page shows "waiting for confirmation" and offers no other method. A payment
  stuck in `created` with an attempt running past `YOON_SWEEP_PENDING_AFTER` (crash mid-call) is
  moved to `pending` by the sweep and settled from the provider's status API.
- The public checkout endpoints answer 429 (`rate_limited`) beyond `YOON_CHECKOUT_RATE_LIMIT`
  requests per minute per client address (default 300, `0` = off), per instance. Behind the bundled
  Caddy the client address comes from `X-Forwarded-For` (Yoon trusts it only from private-network
  proxies, `SERVER_FORWARD_HEADERS_STRATEGY=native`). If every customer seems to share one address
  (a proxy on a public IP), raise the limit or set it to `0` and limit at the proxy.
- The token in the page URL grants that one checkout only (see it, choose a method while the
  payment waits). It is part of `checkout_url` in the API and in webhook payloads; treat it like the
  order link it is.

## Rotate the admin token

Change `YOON_ADMIN_TOKEN` in `.env`, `docker compose up -d`.

## A payout needs review

Alert `YoonPayoutNeedsReview`, or:

```sh
curl -H "Authorization: Bearer $ADMIN" "$YOON/admin/v1/payouts?needs_review=true"
```

Yoon could not learn whether the money left. **Check the provider's dashboard** for the payout
(its id is Yoon's reference), then record what you found:

```sh
curl -X POST -H "Authorization: Bearer $ADMIN" -H "Content-Type: application/json" \
  "$YOON/admin/v1/payouts/po_…/resolve" \
  -d '{"status":"paid","note":"Seen on the PayDunya dashboard, transaction 88123"}'
```

Never resolve as `failed` without checking: the application may pay again.

## Replay dead letters

Alert `YoonDeadEvents`: an application's webhook endpoint refused events 12 times. Fix the
endpoint, then:

```sh
curl -H "Authorization: Bearer $ADMIN" "$YOON/admin/v1/dead-letters"
curl -X POST -H "Authorization: Bearer $ADMIN" "$YOON/admin/v1/dead-letters/evt_…/replay"
```

Applications can also catch up by listing `GET /v1/events`.

## A provider is failing

Alert `YoonProviderUnknownOutcomes`. Payments sent to it stay `pending` (never retried elsewhere)
and are settled by the sweep once it answers. New payments move to the next provider once its
circuit breaker opens. Check the provider's status page; lower its priority or remove it from the
application's settings if the outage lasts.

## Amount mismatch

Alert `YoonAmountMismatch`: a provider confirmed a different amount. Yoon did **not** settle the
payment. Find it in the logs (`ALERT amount_mismatch resource=pay_…`) and its history
(`GET /v1/payments/{id}/events`), then settle the difference with the customer outside Yoon.

## Restore a backup

```sh
docker compose stop yoon
docker compose exec backup sh -c 'dropdb -h postgres -U yoon yoon && createdb -h postgres -U yoon yoon \
  && pg_restore -h postgres -U yoon -d yoon --no-owner /backups/yoon-<timestamp>.dump'
docker compose start yoon
```

After a restore, payments made since the backup exist only at the providers: reconcile with
their dashboards. Test this procedure on a spare machine before you need it.

## Logs

`docker compose logs yoon`. Phone numbers are masked and secrets never logged. Lines starting
`ALERT` are the ones to act on.
