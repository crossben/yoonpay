# Wave (direct)

Module: `yoon-providers/yoon-provider-wave` · id: `wave` · method: `wave` · decision: [ADR-0020](../adr/0020-wave-provider.md)

Wave through its own Business API (docs.wave.com), with your own **Wave Business** account and an
API key from the Wave Business portal. Wave through PayDunya, DexPay or NabooPay still works:
all use the method `wave`, so Yoon can fail over between them.

> **Status:** built from Wave's public documentation and tested against a simulated API
> (WireMock). It has **not yet been run against a Wave Business account**.

## What Yoon uses

| Operation | Supported | How |
| --- | --- | --- |
| Collect | SN, CI, ML, BF (those you configure) · XOF | Checkout session; the customer opens `wave_launch_url` in the Wave app. Needs `return_url` |
| Payout | same | `POST /v1/payout` with `Idempotency-Key`, to a phone number |
| Refund | **Full amount only** | `POST /v1/checkout/sessions/{id}/refund`. A partial refund is refused (`partial_refund_not_supported`): send the rest as a payout |

## Configuration

```sh
YOON_APPS_SHOP_PROVIDERS_WAVE_PRIORITY=1
YOON_APPS_SHOP_PROVIDERS_WAVE_CREDENTIALS_API_KEY=wave_sn_prod_…     # Wave Business portal → Developers
YOON_APPS_SHOP_PROVIDERS_WAVE_CREDENTIALS_WEBHOOK_SECRET=wave_sn_WHS_…  # the webhook's secret (runbook)
YOON_APPS_SHOP_PROVIDERS_WAVE_CREDENTIALS_COUNTRIES=SN                # optional, comma-separated: SN, CI, ML, BF
```

Give the API key the permissions you use (checkout, payouts). A key belongs to one country's
account: list only that country.

## Status mapping

Checkout sessions (`GET /v1/checkout/sessions/{id}` → `payment_status`, `checkout_status`):

| Wave | Yoon |
| --- | --- |
| `succeeded` | succeeded |
| `cancelled` | failed |
| `processing`, session `expired` | expired |
| `processing`, otherwise | pending |
| anything else | no answer |

Payouts (`GET /v1/payout/{id}` → `status`):

| Wave | Yoon |
| --- | --- |
| `processing` | processing |
| `succeeded` | paid |
| `failed` | failed |
| `reversed`, anything else | no answer (a reversal outside Yoon needs a human) |

Refunds: Wave has no refund status. The refund call is idempotent and answers 200 once the
payment is refunded, so Yoon asks again with the same call: 200 → refunded.

Tested in `WaveStatusTest`.

## Quirks

- **Yoon's reference is `client_reference`** on sessions and payouts (and the payout's
  `Idempotency-Key`): a lost answer is found by search.
- Wave requires both `success_url` and `error_url`; Yoon sends the payment's `return_url` for
  both. A payment without `return_url` is refused (`return_url_required`) and can fail over.
- Amounts are sent as whole-number strings (`"1000"`).
- Refusals carry Wave's error code, surfaced as `wave_<code>`, e.g. `wave_insufficient_funds`.
- A payout answered 409 (e.g. `idempotency-mismatch`) or 5xx is **unknown**, never failed: Wave
  says a payout can exist even when no id came back.
- **Callbacks:** `Wave-Signature: t=<time>,v1=<hex>` — HMAC-SHA256 of the timestamp followed by
  the raw body; any `v1` may match (key rotation). Webhooks set up with Wave's "shared secret"
  method (`Authorization: Bearer <secret>`) are accepted too. Yoon finds the session from
  `data.id` and re-confirms through the status API.
