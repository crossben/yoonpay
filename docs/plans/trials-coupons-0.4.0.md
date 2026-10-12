# Plan — trials and coupons in 0.4.0

Design: [ADR-0028](../adr/0028-trials.md) (trials), [ADR-0029](../adr/0029-coupons.md) (coupons).
Status: **proposed — for review before any code.** Automatic charging (Stripe first) moves to 0.5.0.

Decisions taken (2026-10-12): an unpaid trial ends in `past_due` (grace) then `expired`;
coupons: percent, fixed amount, 100% allowed, duration once / N periods / forever; version 0.4.0.

## API (all additive)
| Method | Path | Notes |
| --- | --- | --- |
| POST | `/v1/coupons` | `{code, percent_off \| amount_off, duration, duration_in_periods?, max_redemptions?, redeem_by?, plans?, metadata?}` |
| GET | `/v1/coupons` | `?code=` exact (case-insensitive), `?active=` |
| GET / PATCH | `/v1/coupons/{id}` | PATCH: `active`, `max_redemptions` (up only), `metadata` |
| POST | `/v1/subscriptions` | + `trial_days?`, `coupon?` |
| POST / DELETE | `/v1/subscriptions/{id}/discount` | apply / remove a coupon for future invoices |
| PATCH | `/v1/plans/{id}` | + `trial_days` |
| GET | `/v1/exports/coupons.csv` | redemptions per coupon |

New: status `trialing`; event `subscription.trial_will_end`; fields `trial_end`, `discount`
(subscription), `subtotal`, `discount_amount`, `discount` (invoice), `trial_days` (plan).

## Milestones (each: tests + docs + CHANGELOG, then stop for review/commit)
1. **Core:** `TRIALING` in `SubscriptionStatus` (full matrix), stacking from the trial end;
   `Discounts` (percent floor, amount cap, periods remaining) — pure and exhaustively tested.
2. **Coupons:** migration `V8`, coupons API (immutable terms trigger, case-insensitive codes),
   contract, regenerated clients.
3. **Trials:** create with trial, one trial per customer and plan, renew during the trial, sweep
   (reminder, trial end → `past_due`), events.
4. **Discounts on invoices:** redemption with a locked counter, discount on every invoice kind,
   `periods_remaining`, 0-amount invoices paid at creation, receipt and checkout show the
   discount.
5. **Operator + clients:** dashboard coupons tab, exports, admin views; Python/JS helpers count
   `trialing` as entitled; guide section in `docs/billing.md`; website; LinkedIn post.

## Progress
- M1 core: `TRIALING` (full matrix, entitled, never re-entered), stacking from the trial end,
  `Discounts` (percent floored without overflow, amount capped, periods) — done.

## Tests beyond the usual
- Trial: paid during the trial → period starts at the trial end; unpaid → `past_due` → `expired`;
  second trial on the same plan refused; cancel at trial end → `canceled`; reminder once.
- Coupon: 33% of 15 000 → 4 950 off; amount off larger than the invoice → 0 due → paid at
  creation; `repeating` 3 → three discounted invoices, the fourth full; concurrent redemption of
  the last use → one wins; terms immutable (trigger); wrong currency / plan / expired → 422.
- An old (0.3) client parsing `trialing` does not throw (open set) — and docs say to upgrade.
