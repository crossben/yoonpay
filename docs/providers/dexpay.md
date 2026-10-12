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
in one).

**Run against the sandbox on 2026-10-12**: checkout sessions created for Wave, Orange Money and
Free Money (including 1 XOF); a session paid on the sandbox checkout; the real callback verified
(`x-webhook-signature` = hex HMAC-SHA256 of the raw body with the dashboard's webhook secret) and
the status API returned `completed`; the payment became `succeeded` two seconds after paying. An
idempotent replay returned the same payment. The callback's shape is in
`DexPayProviderTest.RECORDED` (values replaced). Observed:
- The callback is **flat** (`reference`, `status`, `amount` at the top level), not under `data`.
- It also sends `x-webhook-event` and `x-webhook-timestamp`, and a `timestamp` inside the signed
  body. Yoon does not reject old callbacks by time: it never applies a callback without asking
  the status API, so a replayed one cannot change anything.

Payouts were **not** run against the sandbox. Status and error fixtures other than the callback
are still built from production integrations, not recorded.
