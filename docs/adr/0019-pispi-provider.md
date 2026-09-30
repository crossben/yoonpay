# ADR-0019 — PI-SPI provider (BCEAO API Business)

- Status: accepted
- Date: 2026-10-01

## Context
PI-SPI is the BCEAO's instant-payment platform for the UEMOA. Only licensed institutions
("participants": banks, e-money issuers, microfinance and payment institutions) connect to it.
Each participant exposes the same **API Business** (specification 1.5.0, developer portal
`developer.pispi.bceao.int`) to its business clients. Yoon cannot connect to PI-SPI itself; it
acts as a business client of the merchant's own participant.

## Decision
A provider module `yoon-provider-pispi` (id `pispi`, method `pispi`) talks to the merchant's
participant through the API Business.

| Yoon | API Business |
| --- | --- |
| Collect | `POST /demandes-paiements`, category `521` (e-commerce, immediate), `confirmation: false`, sent to the **customer's PI alias** (`payeurAlias`) and paid into the merchant's alias (`payeAlias`). `txId` = Yoon's attempt reference. |
| Payment status | `GET /demandes-paiements/{txId}`: `INITIE`/`ENVOYE` pending, `IRREVOCABLE` succeeded, `REJETE`/`ANNULE` failed. |
| Payout | `POST /paiements-envoyes` to the recipient's alias, `confirmation: false`; status `GET /paiements-envoyes/{txId}`. |
| Refund | "Return of funds": `PUT /paiements/{end2endId}/retours` — full amount only, one per payment, idempotent per `end2endId`. The `end2endId` of the received payment is found with `GET /paiements-recus?txId=<request txId>`; status from `GET /paiements/{end2endId}` (`retourStatut`). |
| Lost answers | Everything is keyed on Yoon's `txId`, so `lookup` always works; a duplicate `txId` is refused with `DU03`. |
| Callbacks | `X-Signature` = HMAC-SHA256 of the raw body with the webhook secret returned when the webhook is registered; body `{data: [events], meta}`; each event carries `txId`. As for every provider, callbacks are hints and are re-confirmed. |
| Auth | OAuth2 client credentials (token URL per participant), `x-api-key`, and **mTLS** with a certificate issued by the BCEAO (PICERT). |

### Customer alias in Yoon's API
The API Business addresses people by **PI alias** (a payment address, a UUID-like key), not by
phone number. Yoon's API gains two optional, additive fields:
`CreatePaymentRequest.customer.pi_alias` and `CreatePayoutRequest.recipient.pi_alias`
(`recipient.phone` becomes optional: one of the two is required). The core SPI carries them as
`CollectRequest.customerAlias` and `PayoutRequest.recipientAlias`. The adapter refuses
(`Rejected`, `PI_ALIAS_REQUIRED`) a request without an alias before any call.

### mTLS in the provider layer
`ProviderHttp` gains a variant built from an `SSLContext`; `yoon-provider-support` builds one
from PEM (PKCS#8 private key, client certificate chain, optional CA to trust). Credentials may
be PEM text or file paths. Each application gets its own HTTP client, because each has its own
certificate.

### Countries
PI-SPI covers the eight UEMOA states (BJ, BF, CI, GW, ML, NE, SN, TG); the provider declares
capabilities for all eight, in XOF.

## Not verified against the sandbox yet
Built from the specification, not from recorded sandbox exchanges:
- that a payment made in answer to a request carries the request's `txId` in `/paiements-recus`;
- the encoding of `X-Signature` (the adapter accepts hex or base64);
- the token endpoint's exact form (standard OAuth2 client credentials is assumed);
- one specification file (`paiements-recus-details.yml`) is not valid YAML; the token URL in the
  specification is a placeholder (`spi.example.com`): each participant has its own.

## Consequences
- Webhook registration (`POST /webhooks`) is done once by the operator (runbook), not by Yoon on
  start-up, and its secret goes into the application's credentials.
- Partial refunds on PI-SPI payments are refused (`PARTIAL_REFUND_NOT_SUPPORTED`); refund the rest
  with a payout.
- In production, the merchant's own bank or e-money issuer must offer the API Business.
