# Plan — customer portal

Design: [ADR-0031](../adr/0031-customer-portal.md). Status: **accepted 2026-10-15** (default: view and pay; the merchant opts in to the rest).

## Milestones (each: tests + docs + CHANGELOG, then stop for review/commit)
1. **Sessions:** migration, `POST /v1/customers/{id}/portal_sessions`, token (hashed), expiry,
   `allow`, cause `portal`; contract, clients.
2. **Read-only portal:** `/checkout/portal/<id>?t=` — subscriptions, invoices, receipts, FR/EN.
3. **Actions:** pay/renew via the checkout and back; cancel / resume; change plan to allowed
   prices; redeem a coupon — each idempotent, recorded with cause `portal`, refused when not
   allowed.
4. **Clients + docs:** Python/JS "open portal" helpers, guide section, website, LinkedIn.

## Progress
- M1 sessions (`V10`, `portal_sessions`, hashed token, TTL, `allow`, cause `portal`), contract,
  regenerated clients — done (262 server tests).
- M2 read-only portal page: subscriptions, open invoice with Pay, invoices and receipts, FR/EN,
  under `CheckoutFilter` — done (267 server tests; checked on a demo server).
- M3 actions: renew, cancel/resume, change to allowed prices, coupon — same service code with a
  `Cause` parameter (the API keeps `api`), idempotent per click, ownership and permission
  checked — done (275 server tests; checked on a demo server).

## Tests beyond the usual
- A session opens only its customer's data; expired, wrong-token and unknown sessions are the
  same 404; another customer's subscription id in an action is refused.
- Each action refused when not in `allow`; a change to a price outside `change_prices` refused.
- Double submit → one action; history shows cause `portal`.
