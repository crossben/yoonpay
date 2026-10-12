# ADR-0029 — Coupons

- Status: accepted (0.4.0)
- Date: 2026-10-12
- Builds on: ADR-0025 (catalog), ADR-0026 (subscriptions), ADR-0027 (invoices)

## Context
Merchants run promotions ("−20% for 3 months", "2 000 XOF off", partner codes) and sometimes give
whole periods away by code. Discounts touch money, so the arithmetic must be integer, the terms
immutable once created, and redemptions countable under concurrency.

## Decision

### Resource `coupon` (`co_…`, per application)
| Field | Rule |
| --- | --- |
| `code` | what customers type; unique per application, case-insensitive, `[A-Z0-9_-]{3,32}` |
| `percent_off` | 1–100, **or** |
| `amount_off` | `{currency, amount}` in minor units (one currency per coupon) |
| `duration` | `once` (next invoice), `repeating` (`duration_in_periods` invoices), `forever` |
| `max_redemptions` | optional total cap |
| `redeem_by` | optional deadline |
| `plans` | optional list of plan ids it is limited to |
| `active` | archive with `false`; coupons are never deleted |

Terms (`percent_off`, `amount_off`, `duration`, `duration_in_periods`, `plans`) are **immutable**
(database trigger, like prices); `active`, `max_redemptions` (only upwards) and `metadata` may
change.

API: `POST/GET /v1/coupons`, `GET/PATCH /v1/coupons/{id}`, `GET /v1/coupons?code=`.

### Redeeming
- At creation: `POST /v1/subscriptions {…, "coupon": "WELCOME20"}`.
- Later: `POST /v1/subscriptions/{id}/discount {"coupon": "…"}` (replaces the current one for
  future invoices); `DELETE /v1/subscriptions/{id}/discount` removes it. An open invoice with no
  payment in progress is voided so the next one is priced with the new discount; with a payment
  in progress the change is refused (`409 payment_in_progress`), never applied mid-payment.
- Refused with `422 coupon_not_applicable` (and a `reason`): unknown or archived code, past
  `redeem_by`, `max_redemptions` reached, plan not in `plans`, `amount_off` in another currency.
- A redemption row is written in the same transaction; `max_redemptions` is enforced by a locked
  counter on the coupon, so two concurrent redemptions of the last one cannot both pass. Lock
  order: subscription → coupon → invoice → payment.
- An archived coupon stops new redemptions; subscriptions that already redeemed it keep their
  discount.
- The subscription carries `discount: {coupon, periods_remaining}` (null when none).

### Applying it to invoices
Every invoice created while a discount is active (first, renewal, upgrade) gets it, and
`periods_remaining` decreases by one **when that invoice is paid** (`forever`: never). Consuming
at payment rather than at creation means a voided invoice (plan change, cancel) never costs the
customer a discounted period (decided while building M4). A discount whose coupon does not fit
the invoice's price (another plan or currency after a change) is skipped for that invoice and
kept for later. The invoice gains `subtotal`,
`discount_amount` and `discount` (`{coupon, code}`); `amount_due = subtotal − discount_amount`.
- Percent: `discount_amount = floor(subtotal × percent_off / 100)` — integers only; rounding
  favours the merchant by at most one minor unit.
- Amount: `min(amount_off, subtotal)`.
- The discount is separate from the price: the invoice keeps its price line (lines are always
  positive) and carries `subtotal`, `discount_amount` and `discount`; the receipt shows it as a
  "Discount" line. Prices stay immutable and the upgrade extra-days calculation (ADR-0026) keeps
  using undiscounted prices.

### 100% off: invoices with nothing to pay
When `amount_due` is 0 the invoice is **paid at creation**, without a payment, in the same
transaction (`paid_at` = now, `payment` null), and the period is added exactly as for a paid
invoice (stacking unchanged). Events: `invoice.paid`, then `subscription.activated` /
`subscription.renewed`. The receipt says "Paid with coupon <code>". No ledger posting.

### Contract
Additive: new resource, new optional request fields, new nullable response fields
(`subtotal`, `discount_amount`, `discount` on invoices; `discount` on subscriptions;
`trial_days` on plans). `Invoice.amount_due` may now be `0`.

## Consequences
- Migration `V8`: `coupons`, `coupon_redemptions`, invoice and subscription columns, the
  `amount_due > 0` check relaxed to `>= 0`.
- Dashboard: coupons tab (read-only); exports gain the discount columns.
