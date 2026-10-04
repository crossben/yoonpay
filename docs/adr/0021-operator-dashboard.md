# ADR-0021 — Read-mostly operator dashboard

- Status: accepted
- Date: 2026-10-04
- Amends: ADR-0013 ("no admin UI in v1")

## Context
Operating Yoon meant curl against `/admin/v1` and SQL. Self-hosters asked for a page that shows
what the instance is doing. The operator API covered only dead letters and payouts needing review;
the rest (payments, refunds, payouts, balances, ledger, status history) was reachable only with an
application's own API key.

## Decision
- `/dashboard` serves a static page (`static/dashboard`: HTML, one vanilla ES module, CSS). No Node
  build, no runtime CDN, no framework. It holds no secret and talks only to `/admin/v1`.
- The operator types `YOON_ADMIN_TOKEN` into the page. It is kept in `sessionStorage` (one tab,
  gone when the tab closes) and sent as `Authorization: Bearer`, the header the operator API
  already checks. It never goes in a URL, cookie or `localStorage`.
- New **read-only** operator routes, contract-first in `api/openapi.yaml` (tag `Admin`, so clients
  are not affected): `GET /admin/v1/applications` and, per application,
  `/admin/v1/applications/{application_id}/{payments,refunds,payouts}`, `…/{kind}/{id}/events`,
  `…/balances`, `…/ledger/entries`. Each resolves the application from the path and runs the very
  query the application gets through `/v1` (`AdminReadController` delegates to the `/v1`
  controllers), so scoping and masking (phones as `+22177***67`) are identical.
- The only action the page offers is **replay a dead letter** (existing, moves no money). Resolving
  a payout stays a deliberate curl call (runbook): a click should not record that money left.
- `YOON_DASHBOARD_ENABLED` defaults to **true**, and the page exists only when the admin API exists
  (token of at least 32 characters); otherwise 404. Default on because the page is inert: without
  the token it shows a sign-in form and reads nothing, and it adds no authentication surface the
  admin API did not already expose. Operators who want no UI at all set it to `false`.
- Dashboard responses carry a strict CSP: `default-src 'none'; script-src 'self'; style-src 'self';
  img-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'`,
  plus `nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `Cache-Control: no-store`.
  The server set no CSP before; this adds one for these paths and changes nothing elsewhere. The
  script writes API data only with `textContent` (a test fails on `innerHTML` and friends).

## Consequences
- A stolen admin token now also opens a UI; it already opened the API. The CSP and text-only
  rendering keep stored data (descriptions, references) from running script in the operator's tab.
- One token, one operator, as in ADR-0013. Multi-user access would need real accounts.
- The page must stay buildless; anything bigger than this calls for a new ADR.
