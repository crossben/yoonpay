# ADR-0023 — CinetPay provider

- Status: accepted
- Date: 2026-10-04

## Context
PayDunya, DexPay and NabooPay only serve Senegal. CinetPay serves Côte d'Ivoire, Senegal, Burkina
Faso, Mali, Togo, Benin, Niger, Cameroon and Guinea with the main mobile-money operators, and
pays out to them. CinetPay's 2026 SDKs (Python, Go, PHP; github.com/cinetpay) use an API v1 at
`api.cinetpay.net` (sandbox) and `api.cinetpay.co` (production) with per-country credentials;
its older public documentation describes a previous API. This adapter follows the SDKs, because
they are CinetPay's current code; its documentation site was unreachable when this was written.

## Decision
A provider module `yoon-provider-cinetpay` (id `cinetpay`); Yoon methods map to CinetPay
operators per country (`orange_money` + `CI` → `OM_CI`, `wave` + `SN` → `WAVE_SN`, …).

| Yoon | CinetPay API v1 |
| --- | --- |
| Auth | `POST /v1/oauth/login` with the country's `api_key` / `api_password` → JWT, cached 12 h. On `EXPIRED_TOKEN`/`INVALID_TOKEN` the cache is dropped and the call is refused (nothing moved; the next call logs in again) — never retried. |
| Collect | `POST /v1/payment` (`merchant_transaction_id`, amount, currency, URLs, `channel: PUSH`, operator when the method maps to one); the customer is sent to `payment_url` |
| Payment status | `GET /v1/payment/{merchant_transaction_id}` → `status` |
| Payout | `POST /v1/transfer` to a phone with the operator code; status `GET /v1/transfer/{merchant_transaction_id}` |
| Refund | None (no refund API) |
| Lost answers | Both are keyed on our `merchant_transaction_id`; a duplicate is refused with `TRANSACTION_EXIST`, which Yoon treats as unknown and resolves through the status API |

### Transaction ids
`merchant_transaction_id` is at most 30 characters; Yoon's references are longer. A reference
longer than 30 maps to `y` + the first 29 hex characters of its SHA-512: fixed, so status and
lookup find it again. The provider reference stored by Yoon is `<country>:<id>`, because each
country has its own credentials.

### Notifications are not trusted
CinetPay authenticates a notification with the `notify_token` it returned when the transaction
was created, not with a signature over the request. Yoon's provider interface verifies a
callback without access to that earlier answer, so CinetPay notifications are reported invalid
and ignored. The reconciliation sweep settles CinetPay transactions through the status API
(after `YOON_SWEEP_PENDING_AFTER`, default 1 minute). Storing the `notify_token` would need a
change to the provider interface; revisit if the delay matters.

### Customer details
CinetPay requires a customer e-mail and name. Yoon has neither, so the application configures a
contact e-mail (`customer-email`) and the name is sent as "Client". CinetPay's page shows its own
form to the customer.

## Consequences
- Payment status from CinetPay carries no amount; Yoon cannot compare it (the hosted page
  charges the amount Yoon sent). Transfers carry their amount and are compared.
- Amount limits from the SDKs are checked before any call: payments 100–2 500 000, transfers
  500–1 500 000.
- DR Congo (CDF, which Yoon counts in cents and CinetPay in whole units) is left out.
- Built from CinetPay's SDK sources and their test fixtures, not from recorded sandbox exchanges.
