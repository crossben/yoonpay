# Roadmap

What comes after 0.4.0, in the order agreed on 2026-11-10. Each item gets its own design (ADR)
and plan before code, then milestones: tests + docs + CHANGELOG, stop for review and commit.
Update the status column as items move.

| # | Item | Status |
| --- | --- | --- |
| 1 | Payment links | **shipped in 0.5.0** — [ADR-0030](../adr/0030-payment-links.md), [plan](payment-links.md) |
| 2 | Customer portal | **designed** — [ADR-0031](../adr/0031-customer-portal.md), [plan](customer-portal.md); M1–M2 done, M3 (actions) next |
| 3 | MCP server for AI agents | planned |
| 4 | Small items (below) | planned, one at a time |
| 5 | Automatic charging, Stripe first (0.5.x/0.6.0) | waits for a merchant that needs it, and a Stripe account |
| 6 | SYSCOHADA accounting export | planned — needs an accountant's review of the default mapping |
| 7 | Daily summary | planned |
| 8 | Marketplace split payments | to study — regulatory question first (see below) |
| 9 | Accord (offline) | to explore, read-only, after payment links (see below) |
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

## 6. SYSCOHADA accounting export
Merchants in the OHADA zone keep books under SYSCOHADA (the accounting system of the OHADA
Uniform Act, revised 2017; SYSCOA is superseded). Today their accountant re-types Wave / Orange
Money statements. Yoon's ledger is already double entry, per provider, with four accounts:
`provider_balance` (money held at the provider), `customer_funds` (the counterpart),
`provider_fees`, `payout_reserved`. An export only needs a mapping to the merchant's chart.
1. ADR: a per-application mapping from Yoon accounts to account numbers and labels, with
   **suggested** defaults (e.g. money at a provider → a class 5 treasury sub-account per wallet,
   provider fees → class 6 bank-type charges, sales → class 7), editable because every chart is
   different. Yoon knows no VAT: sales are exported excluding tax decisions, which stay with the
   accountant. We do not claim "compliant" — the accountant validates the mapping.
2. Exports: a journal (`date, pièce, compte, libellé, débit, crédit`) per period, in plain CSV
   first; then the import formats of the common local tools, once checked with real files.
3. A monthly summary per provider: collected, fees, refunded, paid out, closing balance.
4. Review the default mapping with a Senegalese chartered accountant before calling it a default.

## 7. Daily summary
"Yesterday: 250 000 XOF collected, 3 failed, 1 unknown flagged." Yoon sends no e-mail (by design):
1. A `report.daily` event (signed, like every event) the application turns into a message.
2. The same figures on the dashboard home, in the application's time zone.

## 8. Marketplace split payments
A customer pays 10 000; 9 000 goes to the seller, 1 000 stays as the platform's fee.
**Regulatory question first:** holding and redistributing money for third parties can make the
operator a payment service provider under BCEAO rules. Before any design, find out which
providers offer native splitting (the money never transits the platform) and what licence the
other path needs. Then: ADR, ledger accounts per seller, payouts, reporting.

## 9. Accord in the dashboard (exploration)
Accord (offline-first sync) fits a place where people work on bad connections and must not lose
work. The operator dashboard is not that place today: it must show the **current** truth, and its
only writes (replay, payment links) must reach the server, not merge later. Candidate instead: a
read-only offline cache of recent payments and payment links for a merchant at a counter, synced
when back online, with every write still going to the API. Explore after payment links ship;
no offline writes that touch money.

## Small items added (2026-10-15)
- [ ] "Reconcile now" for one stuck payment, payout or refund: dashboard button + admin API.
- [ ] Laravel: `php artisan yoon:install` (publish config, check `.env`), and a Blade component
      for a checkout button that creates a hosted payment through a controller.

## Considered and not planned
- Provider credentials or API keys editable in the dashboard: credentials stay in environment
  variables, never in a web form (an admin token leak must not become a credential leak).
- "Charge every month automatically" over mobile money: wallets have no auto-debit; prepaid
  subscriptions (0.3/0.4) are the answer, card auto-renewal is item 5.
- Multi-country: already there through CinetPay (9 countries, XOF, XAF, GNF…); other providers
  are limited by what they support, not by Yoon.

## Done since 0.4.0
- NabooPay: `FEES_CUSTOMER_SIDE` per application (merchant may absorb the fee).
