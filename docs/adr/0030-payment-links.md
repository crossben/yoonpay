# ADR-0030 — Payment links

- Status: accepted (2026-11-10)
- Date: 2026-11-10
- Builds on: ADR-0024 (hosted checkout)

## Context
Many merchants in the region sell over WhatsApp, Instagram or in person and have no developer.
They need a link (or a QR code) they can share, that a customer opens and pays with Wave, Orange
Money or a card. Yoon's hosted checkout already does the paying; what is missing is a durable,
shareable entry point that creates the payment when someone opens it.

## Decision

### Resource `payment_link` (`plink_…`, per application)
| Field | Rule |
| --- | --- |
| `url` | `<YOON_PUBLIC_URL>/l/<code>`; `code` is 22 random URL-safe characters (unguessable, not a token to keep secret: whoever has the link may pay) |
| `amount` | fixed amount in minor units, **or** `amount: null` with `min_amount` / `max_amount`: the customer types it |
| `currency`, `country` | required (like a payment) |
| `description` | shown on the page and on the payment |
| `method` | optional: limit to one method; default any |
| `max_payments` | optional: `1` = single use; null = reusable |
| `expires_at` | optional |
| `return_url` | optional: where the customer goes after paying |
| `active` | archive with `false` (the page then says "this link is no longer active"); never deleted |
| `metadata` | the merchant's own |
| `payments_count`, `amount_collected` | read-only, succeeded payments only |

API: `POST/GET /v1/payment_links`, `GET/PATCH /v1/payment_links/{id}` (PATCH: `active`,
`description`, `expires_at`, `max_payments`, `metadata`; the amount never changes — create a new
link). `Payment` gains `payment_link` (nullable).

### Opening a link
`GET /l/<code>` (public, same CSP, rate limit and identical 404s as the checkout): shows the
merchant name, description and amount — or an amount field within the bounds — and a "Pay"
button. Pressing it creates an ordinary **hosted payment** (reference `<link id>:<n>`, idempotent
per browser attempt) and redirects to its `/checkout/…` page; from there everything is the
existing checkout: method choice, provider, callback re-confirmed with the provider's API.
Nothing new touches money.

- An inactive, expired or used-up link shows a clear page instead (not a 404, so customers
  understand); unknown codes are 404.
- Single use (`max_payments: 1`) counts **succeeded** payments. Two customers paying at the same
  moment may both succeed — money received is never refused; the link then shows used up and the
  merchant sees two payments. This is documented, not prevented (preventing it would need a lock
  held across a provider round-trip) — decided 2026-11-10.

### Events
No new event types: each payment is an ordinary payment with `payment_link` set
(`payment.succeeded` etc.). `payment_link` objects are not delivered as events in this version.

### Creating links without a developer
Merchants without developers run Yoon through an operator (often themselves). Options:
Decided: option 1.
1. **(Chosen)** The operator dashboard gets a small "Payment links" tab that can **create and
   archive** links (through the admin API with the admin token) and shows each link's URL and QR
   code. The dashboard stops being strictly read-only, for this one resource; it never moves
   money.
2. CLI only: `yoon links create <app> --amount 5000 --description …` (prints URL + QR as text).
3. API only (the merchant needs a developer, which is what this feature is meant to avoid).

### QR codes
`GET /l/<code>/qr.svg` (public): an SVG QR code of the link URL, generated server-side with a small
dependency-free encoder (Nayuki's `qrcodegen`, MIT), so the dashboard and any printed material can
use it.

## Consequences
- Migration: `payment_links`, `payments.payment_link_id`.
- Additive API; new response fields optional (ADR-0029 lesson).
- Exports: `payment_links.csv`; `payments.csv` gains `payment_link`.
