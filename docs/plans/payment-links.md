# Plan — payment links

Design: [ADR-0030](../adr/0030-payment-links.md). Status: **proposed — for review before any code.**

## Milestones (each: tests + docs + CHANGELOG, then stop for review/commit)
1. **Schema + API:** migration, `/v1/payment_links` (create, list, get, patch), `Payment.payment_link`,
   contract, regenerated clients.
2. **Public page:** `/l/<code>` (fixed or customer-chosen amount, FR/EN, mobile), creates a hosted
   payment and redirects to the checkout; states for inactive / expired / used up; rate limit;
   `qr.svg`.
3. **Operator:** dashboard "Payment links" tab (create, archive, URL, QR) through the admin API;
   CLI `yoon links create`; exports.
4. **Docs + clients + website:** guide section, client helpers if useful, website page, LinkedIn post.

## Tests beyond the usual
- Fixed and customer-chosen amounts (bounds enforced server-side, integer minor units).
- Opening twice without paying → payments created only when "Pay" is pressed; refresh does not
  create a second one (idempotent per attempt).
- Single use: after one success the page shows used up; two concurrent successes both kept.
- Archived / expired links; unknown code → 404; rate limit.
- A paid link payment is an ordinary payment: callback re-confirmed, ledger, events.
