# PI-SPI (BCEAO instant payments)

Module: `yoon-providers/yoon-provider-pispi` · id: `pispi` · method: `pispi` · decision: [ADR-0019](../adr/0019-pispi-provider.md)

PI-SPI is the BCEAO's instant-payment platform for the UEMOA. Only licensed institutions
(banks, e-money issuers, microfinance and payment institutions) connect to it. Yoon talks to
**your own institution** through the **API Business** it exposes to business clients
(specification 1.5.0). Your bank or e-money issuer must offer it and give you access.

> **Status:** built from the specification and tested against a simulated API (WireMock, including
> mutual TLS). It has **not yet been run against the PI-SPI sandbox**. See "Not verified yet".

## What Yoon uses

| Operation | Supported | How |
| --- | --- | --- |
| Collect | BJ, BF, CI, GW, ML, NE, SN, TG · XOF · method `pispi` | Payment request (`POST /demandes-paiements`, category `521`, e-commerce) sent to the **customer's PI alias**; the customer approves it in their banking or wallet app |
| Payout | same | `POST /paiements-envoyes` from your alias to the **recipient's PI alias** |
| Refund | **Full amount only** | Return of funds: `PUT /paiements/{end2endId}/retours`. A partial refund is refused (`partial_refund_not_supported`): send the rest as a payout |

PI-SPI addresses people by **PI alias** (a payment address), not by phone number. Pass it in the
API:

```json
POST /v1/payments  { "amount": 5000, "currency": "XOF", "country": "SN", "method": "pispi",
                     "customer": { "pi_alias": "…" } }
POST /v1/payouts   { "amount": 5000, "currency": "XOF", "country": "SN", "method": "pispi",
                     "recipient": { "pi_alias": "…" } }
```

Without an alias the call is refused before anything is sent (`pi_alias_required`).

## Configuration

Your institution gives you the URLs, the OAuth2 client, the API key and your merchant alias; the
BCEAO issues the client certificate used for mutual TLS.

```sh
YOON_APPS_SHOP_PROVIDERS_PISPI_PRIORITY=1
YOON_APPS_SHOP_PROVIDERS_PISPI_CREDENTIALS_BASE_URL=https://…/piz/v1   # your institution's API Business
YOON_APPS_SHOP_PROVIDERS_PISPI_CREDENTIALS_TOKEN_URL=https://…/oauth/token
YOON_APPS_SHOP_PROVIDERS_PISPI_CREDENTIALS_CLIENT_ID=…
YOON_APPS_SHOP_PROVIDERS_PISPI_CREDENTIALS_CLIENT_SECRET=…
YOON_APPS_SHOP_PROVIDERS_PISPI_CREDENTIALS_API_KEY=…                   # sent as x-api-key
YOON_APPS_SHOP_PROVIDERS_PISPI_CREDENTIALS_MERCHANT_ALIAS=…            # your PI alias (payee of collections, payer of payouts)
YOON_APPS_SHOP_PROVIDERS_PISPI_CREDENTIALS_WEBHOOK_SECRET=…            # returned when you register the webhook (runbook)
# mutual TLS: PEM text or a file path
YOON_APPS_SHOP_PROVIDERS_PISPI_CREDENTIALS_CLIENT_CERT=/run/secrets/pispi-client.crt   # certificate (+ chain)
YOON_APPS_SHOP_PROVIDERS_PISPI_CREDENTIALS_CLIENT_KEY=/run/secrets/pispi-client.key    # PKCS#8 (BEGIN PRIVATE KEY)
YOON_APPS_SHOP_PROVIDERS_PISPI_CREDENTIALS_CA_CERT=/run/secrets/pispi-ca.crt           # optional: CA to trust for the server
# optional: OAuth2 scopes (default: demande_paiement.write demande_paiement.read paiement.write paiement.read retour_fonds.write)
YOON_APPS_SHOP_PROVIDERS_PISPI_CREDENTIALS_SCOPE=…
```

`CLIENT_CERT` and `CLIENT_KEY` go together. A key in the older `BEGIN RSA PRIVATE KEY` form must be
converted first: `openssl pkcs8 -topk8 -nocrypt -in old.key -out client.key`.

## Status mapping

Payment requests (`GET /demandes-paiements/{our reference}` → `statut`):

| PI-SPI | Yoon |
| --- | --- |
| `INITIE`, `ENVOYE` | pending |
| `IRREVOCABLE` | succeeded |
| `REJETE`, `ANNULE` | failed |
| anything else | no answer |

Payouts (`GET /paiements-envoyes/{our reference}` → `statut`):

| PI-SPI | Yoon |
| --- | --- |
| `INITIE`, `ENVOYE` | processing |
| `IRREVOCABLE` | paid |
| `REJETE`, `ANNULE` | failed |
| anything else | no answer |

Refunds (`GET /paiements/{end2endId}` → `retourStatut`):

| PI-SPI | Yoon |
| --- | --- |
| `INITIE`, `ENVOYE` | pending |
| `IRREVOCABLE` | refunded |
| `REJETE` | failed |
| anything else | no answer |

Tested in `PiSpiStatusTest`.

## Quirks

- **Everything is keyed on Yoon's own reference** (`txId`), so a lost answer can always be looked
  up. A duplicate `txId` (`DU03`) means an earlier attempt arrived: Yoon asks the status API
  instead of guessing.
- Refusals carry an ISO reason code (`statutRaison`), surfaced as `pispi_<code>`, for example
  `pispi_be23` (unknown alias).
- A refund first finds the customer's payment with `GET /paiements-recus?txId=<request txId>` to
  get its `end2endId`, then asks for the return of funds. A return is always the full amount and
  is idempotent per payment.
- The access token is cached until 30 s before it expires. No token means nothing was sent: the
  call is refused and can fail over.
- **Callbacks:** `POST` with `X-Signature`, HMAC-SHA256 of the raw body with the webhook secret.
  The specification does not say hex or base64, so both are accepted. The body is
  `{data: [events], meta}`; Yoon finds the record from the first event's `txId` and re-confirms
  through the status API, as for every provider.

## Not verified yet

Built from the specification, not from recorded sandbox exchanges:

- that a payment made in answer to a request carries the request's `txId` in `/paiements-recus`
  (refunds depend on it);
- the encoding of `X-Signature`;
- the token endpoint's exact form (standard OAuth2 client credentials, HTTP Basic client
  authentication, is assumed);
- the token URL in the specification is a placeholder: each institution has its own.
