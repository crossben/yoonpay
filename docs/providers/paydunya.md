# PayDunya

Module: `yoon-providers/yoon-provider-paydunya` · id: `paydunya`

## What Yoon uses

| Operation | Supported | How |
| --- | --- | --- |
| Collect | Senegal, XOF: `wave`, `orange_money`, `free_money`, `card` | Hosted checkout invoice (`checkout-invoice/create`); the customer chooses on PayDunya's page |
| Payout | Senegal, XOF: `wave`, `orange_money`, `free_money` | Disburse v2: `get-invoice` then `submit-invoice` |
| Refund | **No** | PayDunya has no refund API. Refund by sending a payout to the customer |

## Configuration

```sh
YOON_APPS_SHOP_PROVIDERS_PAYDUNYA_PRIORITY=1
YOON_APPS_SHOP_PROVIDERS_PAYDUNYA_CREDENTIALS_MASTER_KEY=…
YOON_APPS_SHOP_PROVIDERS_PAYDUNYA_CREDENTIALS_PRIVATE_KEY=…
YOON_APPS_SHOP_PROVIDERS_PAYDUNYA_CREDENTIALS_TOKEN=…
YOON_APPS_SHOP_PROVIDERS_PAYDUNYA_CREDENTIALS_MODE=live      # default: test (sandbox)
YOON_APPS_SHOP_PROVIDERS_PAYDUNYA_CREDENTIALS_STORE_NAME=My shop   # optional
```

Set `YOON_PUBLIC_URL`: Yoon sends `callback_url = <public url>/v1/hooks/paydunya/<application id>`.

## Status mapping

Checkout invoices (`checkout-invoice/confirm/{token}` → `status`):

| PayDunya | Yoon |
| --- | --- |
| `pending` | pending |
| `completed` | succeeded |
| `cancelled`, `canceled`, `failed` | failed |
| `expired` | expired |
| anything else | no answer (kept pending, alert) |

Disburse (`disburse/check-status` → `status`):

| PayDunya | Yoon |
| --- | --- |
| `success`, `completed` | paid |
| `failed`, `failure`, `cancelled`, `canceled`, `declined`, `rejected`, `error` | failed |
| `pending`, `processing`, `created` | processing |
| anything else | no answer |

Tested in `PayDunyaStatusTest`.

## Quirks

- **The IPN "hash" is `sha512(master key)`**: a constant that does not depend on the body, so
  anyone who has seen one callback can forge any other — it authenticates nothing. PayDunya offers
  nothing stronger. Yoon checks it, but a callback only ever makes Yoon ask the confirm API about a
  record it already has, by the reference Yoon stored: a forged callback cannot settle anything.
- **A checkout whose create answer is lost cannot be matched later.** PayDunya has no search by
  Yoon's reference (sent as `custom_data.yoon_attempt`), so the payment stays pending without a
  token and expires. Low risk: the customer never received the checkout URL.
- Callbacks arrive as JSON or form data; `hash` sits in `data` or at the root; `data` may be a
  JSON string. Checkout callbacks carry `data.invoice.token`; disburse callbacks `token` or
  `disburse_invoice`, sometimes flat and unsigned.
- Before `get-invoice`, PayDunya probes the callback URL with an empty body and requires a 2xx —
  Yoon's hook endpoint answers 200.
- Success is HTTP 2xx **and** `response_code == "00"`. The checkout URL is in `response_text`.
- Payout `account_alias` is the local number without `221`; minimum 200 XOF; the token is
  `disburse_token`, sometimes `token`.
- `get-invoice` moves no money (a failure there is a definite rejection). `submit-invoice` does:
  a lost answer is unknown, and the disburse token is kept for the status check.
- The disburse API has no sandbox host.
- Not used: SoftPay (direct push to the wallet, live mode only).

## Evidence

Built from PayDunya integrations running in production (three applications). The WireMock
fixtures reproduce the shapes those integrations handle; they are **not recorded sandbox
responses** — replace them with recordings when sandbox access is available.
