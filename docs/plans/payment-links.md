# Plan — payment links

Design: [ADR-0030](../adr/0030-payment-links.md). Status: **accepted 2026-11-10** (dashboard may create/archive links; concurrent single-use successes are kept).

## Milestones (each: tests + docs + CHANGELOG, then stop for review/commit)
1. **Schema + API:** migration, `/v1/payment_links` (create, list, get, patch), `Payment.payment_link`,
   contract, regenerated clients.
2. **Public page:** `/l/<code>` (fixed or customer-chosen amount, FR/EN, mobile), creates a hosted
   payment and redirects to the checkout; states for inactive / expired / used up; rate limit;
   `qr.svg`.
3. **Operator:** dashboard "Payment links" tab (create, archive, URL, QR) through the admin API;
   CLI `yoon links create`; exports.
4. **Docs + clients + website:** guide section, client helpers if useful, website page, LinkedIn post.

## Progress
- M1 schema (`V9`), `/v1/payment_links` API, `Payment.payment_link`, contract, regenerated
  clients — done (243 server tests, all client suites, oasdiff clean against v0.4.0).
- M2 public page `/l/<code>` (fixed or customer-chosen amount, FR/EN), "Pay" through the hosted
  checkout with per-attempt idempotency, states, `qr.svg` from the public URL, under
  `CheckoutFilter` — done (251 server tests; checked live on a demo server).
- M3 dashboard tab (create, archive, URL, QR, totals), admin API, CLI `links create`, exports —
  done (256 server tests).

## Tests beyond the usual
- Fixed and customer-chosen amounts (bounds enforced server-side, integer minor units).
- Opening twice without paying → payments created only when "Pay" is pressed; refresh does not
  create a second one (idempotent per attempt).
- Single use: after one success the page shows used up; two concurrent successes both kept.
- Archived / expired links; unknown code → 404; rate limit.
- A paid link payment is an ordinary payment: callback re-confirmed, ledger, events.
