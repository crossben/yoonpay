# NabooPay

Module: `yoon-providers/yoon-provider-naboopay` · id: `naboopay`

## What Yoon uses

| Operation | Supported | How |
| --- | --- | --- |
| Collect | Senegal, XOF: `wave`, `orange_money`, `free_money`, `card` | `transaction/create-transaction`, hosted checkout |
| Payout | **No** (v1) | The cashout API has no idempotency key and no callback: a lost answer could pay twice |
| Refund | **No** | No refund API |

## Configuration

```sh
YOON_APPS_SHOP_PROVIDERS_NABOOPAY_PRIORITY=3
YOON_APPS_SHOP_PROVIDERS_NABOOPAY_CREDENTIALS_API_KEY=…
YOON_APPS_SHOP_PROVIDERS_NABOOPAY_CREDENTIALS_WEBHOOK_SECRET=…
```

There is no sandbox host: test or live is decided by the key. NabooPay's callback URL is set in
its dashboard: `<YOON_PUBLIC_URL>/v1/hooks/naboopay/<application id>`.

## Wallets

| Yoon method | NabooPay `method_of_payment` |
| --- | --- |
| `wave` | `WAVE` |
| `orange_money` | `ORANGE_MONEY` |
| `free_money` | `FREE_MONEY` |
| `card` | `BANK` |

## Status mapping

`GET /transaction/get-one-transaction?order_id=…` → `transaction_status`:

| NabooPay | Yoon |
| --- | --- |
| `paid`, `done` | succeeded |
| `pending` | pending |
| `part_paid` | pending — **never settled**, no amount confirmed |
| `cancel`, `cancelled`, `canceled`, `failed`, `failure`, `rejected`, `refused`, `error` | failed |
| `expired` | expired |
| anything else | no answer |

The SDK documents `pending`, `paid`, `done`, `part_paid`; the failure spellings are defensive.
Tested in `NabooPayStatusTest`.

## Quirks

- The wallet is sent from the table above, never derived from a slug (a production bug once
  sent `ORANGE-MONEY` and only Wave worked).
- Amounts are JSON integers in whole units; currencies with minor units are refused.
- The API takes no merchant reference; `order_id` is the only link. A lost create answer cannot
  be looked up — but the customer never received a checkout link, so nothing can have been paid;
  the payment expires.
- Callbacks: `X-Signature` = hex HMAC-SHA256 of the raw body with the webhook secret.
- Errors are FastAPI-style: `detail` as a string or a list of `{msg}`.

## Evidence

Built from NabooPay integrations running in production and the published `naboopay` SDK models.
WireMock fixtures are **not recorded sandbox responses**.
