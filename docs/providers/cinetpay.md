# CinetPay

Module: `yoon-providers/yoon-provider-cinetpay` · id: `cinetpay` · decision: [ADR-0023](../adr/0023-cinetpay-provider.md)

Mobile money in nine West and Central African countries through CinetPay's API v1, the one
CinetPay's own SDKs use (`api.cinetpay.net` sandbox, `api.cinetpay.co` production).

> **Status:** built from CinetPay's SDK sources and their test fixtures and tested against a
> simulated API (WireMock). It has **not yet been run against the CinetPay sandbox**.

## What Yoon uses

| Operation | Supported | How |
| --- | --- | --- |
| Collect | The countries you configure, with their operators (below) | `POST /v1/payment`; the customer is sent to CinetPay's page. Needs `return_url`. 100 – 2 500 000 |
| Payout | same | `POST /v1/transfer` to a phone number. 500 – 1 500 000 |
| Refund | **No** | No refund API: refund by payout |

| Country | Currency | Yoon methods (CinetPay operator) |
| --- | --- | --- |
| CI | XOF | `orange_money` (OM_CI), `moov` (MOOV_CI), `mtn` (MTN_CI), `wave` (WAVE_CI) |
| SN | XOF | `orange_money` (OM_SN), `free_money` (FREE_SN), `expresso` (EXPRESSO_SN), `wave` (WAVE_SN) |
| BF | XOF | `orange_money`, `moov`, `wave` |
| ML | XOF | `orange_money`, `moov` |
| TG | XOF | `moov`, `tmoney` |
| BJ | XOF | `moov`, `mtn` |
| NE | XOF | `airtel`, `moov`, `zamani` |
| CM | XAF | `orange_money`, `mtn` |
| GN | GNF | `orange_money`, `mtn` |

## Configuration

CinetPay issues one API key and password **per country**.

```sh
YOON_APPS_SHOP_PROVIDERS_CINETPAY_PRIORITY=3
YOON_APPS_SHOP_PROVIDERS_CINETPAY_CREDENTIALS_COUNTRIES=CI,SN
YOON_APPS_SHOP_PROVIDERS_CINETPAY_CREDENTIALS_API_KEY_CI=sk_live_…
YOON_APPS_SHOP_PROVIDERS_CINETPAY_CREDENTIALS_API_PASSWORD_CI=…
YOON_APPS_SHOP_PROVIDERS_CINETPAY_CREDENTIALS_API_KEY_SN=sk_live_…
YOON_APPS_SHOP_PROVIDERS_CINETPAY_CREDENTIALS_API_PASSWORD_SN=…
YOON_APPS_SHOP_PROVIDERS_CINETPAY_CREDENTIALS_CUSTOMER_EMAIL=payments@your-shop.example   # CinetPay requires a customer e-mail
```

`sk_test_` keys select the sandbox automatically.

## Status mapping

Payments (`GET /v1/payment/{merchant_transaction_id}` → `status`):

| CinetPay | Yoon |
| --- | --- |
| `SUCCESS` | succeeded |
| `INITIATED`, `PENDING` | pending |
| `FAILED` | failed |
| `EXPIRED` | expired |
| anything else | no answer |

Transfers (`GET /v1/transfer/{merchant_transaction_id}` → `status`):

| CinetPay | Yoon |
| --- | --- |
| `SUCCESS` | paid |
| `INITIATED`, `PENDING` | processing |
| `FAILED` | failed |
| anything else | no answer |

Tested in `CinetPayStatusTest`.

## Quirks

- **Notifications are not used.** CinetPay authenticates them with a token from the create
  answer, not a signature Yoon can check, so Yoon ignores them and the reconciliation sweep asks
  the status API instead (after `YOON_SWEEP_PENDING_AFTER`, default 1 minute). Expect statuses
  to update about a minute after the customer pays.
- `merchant_transaction_id` is at most 30 characters: longer Yoon references are mapped to a
  fixed 30-character id (ADR-0023). A duplicate (`TRANSACTION_EXIST`) is **unknown**, never
  failed.
- An expired token refuses the call (nothing moved) and the next call logs in again; Yoon never
  retries on its own.
- Payment status carries no amount, so Yoon cannot compare it; transfers carry it and are
  compared.
- DR Congo is left out: its currency (CDF) is counted differently by Yoon and CinetPay.
