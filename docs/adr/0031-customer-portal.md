# ADR-0031 — Customer portal

- Status: accepted (2026-10-15)
- Date: 2026-10-15
- Builds on: ADR-0024 (hosted checkout), ADR-0026 (subscriptions), ADR-0027 (receipts), ADR-0029 (coupons)

## Context
Every merchant with subscriptions builds the same "My subscription" page: plan, renewal date, a
pay button, invoices, cancel. Yoon already has every action behind the API and public,
token-scoped pages (checkout, receipts) with strict rules. A portal page reuses both.

## Decision

### Portal sessions
The merchant's server asks for a session when its user clicks "Manage my subscription":

```http
POST /v1/customers/{id}/portal_sessions
{"return_url": "https://app.example/account",
 "allow": {"cancel": true, "change_prices": ["price_…", "price_…"], "coupons": false}}
→ 201 {"url": "<YOON_PUBLIC_URL>/checkout/portal/ps_…?t=…", "expires_at": "…"}
```

- The URL carries a random token (stored hashed); it opens **this customer's** portal only and
  expires after **1 hour** (`YOON_APPS_<APP>_BILLING_PORTAL_TTL`, at most 24h). The merchant creates a fresh session
  each time — sessions are cheap, not stored for reuse.
- `allow` is what the customer may do beyond viewing and paying: cancel (at period end) and
  resume; change to one of the listed prices (same rules as `/change`); redeem a coupon code.
  Default: view and pay only. The merchant decides, per session (decided 2026-10-15: whether a
  customer may cancel alone is the merchant's business choice, not Yoon's).

### What the portal shows
The customer's subscriptions (plan, status, paid until, trial end, pending change, discount),
their invoices with receipt links, and the open invoice to pay. FR/EN, phone-sized, same CSP,
rate limit, no-cache and identical 404s as the checkout; no contact details beyond the
customer's name.

### Actions
Each action calls the same service as the API (no second implementation):
- **Pay / renew:** `renew` → redirect to the hosted checkout; back to the portal after.
- **Cancel at period end / resume** (if allowed).
- **Change plan** to an allowed price (upgrade now → pay; downgrade at period end).
- **Redeem a coupon** (if allowed).
Every action is idempotent per page attempt (like payment links) and recorded in the
subscription's history with a new cause **`portal`** (migration widens the `cause` check), so
the merchant sees "canceled by the customer from the portal". Events are the usual
`subscription.*` / `invoice.*`.

## Consequences
- Migration: `portal_sessions` (customer, allowed actions, token hash, expiry), `cause` check.
- API: `POST /v1/customers/{id}/portal_sessions`; public `/checkout/portal/…` pages.
- Clients: a helper that creates a session and redirects (FastAPI, Next.js), like checkout.
