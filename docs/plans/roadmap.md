# Roadmap

What comes after 0.4.0, in the order agreed on 2026-11-10. Each item gets its own design (ADR)
and plan before code, then milestones: tests + docs + CHANGELOG, stop for review and commit.
Update the status column as items move.

| # | Item | Status |
| --- | --- | --- |
| 1 | Payment links | **designed** — [ADR-0030](../adr/0030-payment-links.md), [plan](payment-links.md), waiting for review |
| 2 | Customer portal | planned |
| 3 | MCP server for AI agents | planned |
| 4 | Small items (below) | planned, one at a time |
| 5 | Automatic charging, Stripe first (0.5.x/0.6.0) | waits for a merchant that needs it, and a Stripe account |
| — | Provider runs against real APIs | DexPay (sandbox) and NabooPay (live) done; PayDunya, CinetPay, Wave, Stripe, PI-SPI wait for keys |

## 1. Payment links
A link a merchant creates in the dashboard or with one API call and shares anywhere (WhatsApp,
SMS, a QR code); the customer pays on Yoon's hosted checkout. No integration needed.
1. ADR + plan: fixed amount or customer-chosen amount (min/max), single-use or reusable,
   optional expiry, description, return URL; each paid visit is an ordinary hosted payment linked
   to the link; events `payment_link.*`.
2. Core and schema: link resource, slug/token in the URL, usage counters.
3. API + contract + clients: `/v1/payment_links`.
4. Public page: the link opens a checkout (amount input when the customer chooses), rate-limited.
5. Dashboard: create, list, archive, QR code; exports.
6. Docs, website, LinkedIn.

## 2. Customer portal
A token-scoped page (like receipts) where a customer manages their own subscription.
1. ADR: how the merchant gets a portal URL (`POST /v1/customers/{id}/portal_sessions`, short-lived
   token), what the customer may do.
2. View: plan, status, period, invoices and receipts.
3. Actions: renew (pay), change plan (merchant-allowed prices), cancel at period end / resume,
   apply a coupon.
4. FR/EN, mobile, same CSP and rules as the checkout; docs, website.

## 3. MCP server for AI agents
1. ADR: read-only tools first (payments, subscriptions, invoices, events, "why did this fail"),
   then writes behind explicit confirmation and idempotency (refund, comp, renew link).
2. Server: a separate small process or an endpoint using an API key per application.
3. Docs: setup for Claude and other MCP clients; website page.

## 4. Small items
- [ ] SMS / WhatsApp reminders: a pluggable sender for `renewal_upcoming` / `trial_will_end`
      (Yoon sends nothing today, by design — this is opt-in).
- [ ] PDF receipts (today: print → save as PDF).
- [ ] Revenue view on the dashboard: monthly recurring revenue, churn, past-due amounts.
- [ ] Go and .NET clients (draft notes in `docs/plans/more-clients.md`, not committed).

## Done since 0.4.0
- NabooPay: `FEES_CUSTOMER_SIDE` per application (merchant may absorb the fee).
