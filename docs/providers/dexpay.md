# DexPay (DEXCHANGE Pay)

Module: `yoon-providers/yoon-provider-dexpay` · id: `dexpay`

## What Yoon uses

| Operation | Supported | How |
| --- | --- | --- |
| Collect | Senegal, XOF: `wave`, `orange_money`, `free_money`, `card` | Hosted checkout session; the customer picks the wallet on DexPay's page |
| Payout | Senegal, XOF: `wave`, `orange_money` (others if their operator code is pinned) | `POST /payouts` with `Idempotency-Key` |
| Refund | **No** | No refund API |

## Configuration

```sh
YOON_APPS_SHOP_PROVIDERS_DEXPAY_PRIORITY=2
YOON_APPS_SHOP_PROVIDERS_DEXPAY_CREDENTIALS_API_KEY=…
YOON_APPS_SHOP_PROVIDERS_DEXPAY_CREDENTIALS_API_SECRET=…
YOON_APPS_SHOP_PROVIDERS_DEXPAY_CREDENTIALS_WEBHOOK_SECRET=…   # from the DexPay dashboard
YOON_APPS_SHOP_PROVIDERS_DEXPAY_CREDENTIALS_MODE=live           # default: sandbox
# optional: pin payout operator codes if DexPay renames them
YOON_APPS_SHOP_PROVIDERS_DEXPAY_CREDENTIALS_PAYOUT_OPERATOR_WAVE=wave_sn_payout
YOON_APPS_SHOP_PROVIDERS_DEXPAY_CREDENTIALS_PAYOUT_OPERATOR_ORANGE_MONEY=om_sn_payout
YOON_APPS_SHOP_PROVIDERS_DEXPAY_CREDENTIALS_PAYOUT_OPERATOR_FREE_MONEY=…
```

Sandbox and live use different keys; a live key on the sandbox host looks like a bad key.

## Status mapping

Checkout sessions (`GET /checkout-sessions/{our reference}` → `data.status`):

| DexPay | Yoon |
| --- | --- |
| `success`, `succeeded`, `completed`, `paid` | succeeded |
| `pending`, `initiated`, `processing`, `pending_confirmation` | pending |
| `failed`, `error`, `declined`, `cancelled`, `canceled` | failed |
| `expired` | expired |
| `refunded`, anything else | no answer (alert — a refund outside Yoon needs a human) |

Payouts (`GET /payouts/{our reference}` → `status`):

| DexPay | Yoon |
| --- | --- |
| `completed`, `success`, `successful` | paid |
| `failed`, `cancelled`, `canceled`, `rejected` | failed |
| `pending`, `processing`, `frozen` | processing |
| anything else | no answer |

Tested in `DexPayStatusTest`.

## Quirks

- **Everything is keyed on Yoon's own reference** (checkout `reference`, payout `reference` and
  `Idempotency-Key`), so a lost answer can always be looked up.
- Checkout creation needs only `x-api-key`; status and payouts need `x-api-key` and
  `x-api-secret`.
- **Callback signature:** HMAC-SHA256 of the raw body, hex, in `x-webhook-signature` (older
  docs: `x-dexchange-signature`; sometimes prefixed `sha256=`). The secret is a **separate
  dashboard value** — one production integration tried every API key combination against a real
  callback and none matched. If unset, Yoon falls back to the API secret.
- Payouts: 400/401/402/403/422 are refusals (no money moved); 409 and 5xx are unknown.
- A 404 on a payout status means DexPay never received it. Yoon does **not** resend: the payout
  stays unknown and is flagged for review.
- Payout operator codes (`wave_sn_payout`, `om_sn_payout`) are DexPay's to rename; confirm them
  against the sandbox operator list before going live.

## Evidence

Built from DexPay integrations running in production (collections in two applications, payouts
in one). WireMock fixtures reproduce those shapes; they are **not recorded sandbox responses**.
