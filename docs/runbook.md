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
