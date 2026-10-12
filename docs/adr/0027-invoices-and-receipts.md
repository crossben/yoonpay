# ADR-0027 — Invoices (billing records) and payment receipts

- Status: accepted (shipped in 0.3.0)
- Date: 2026-10-10
- Builds on: ADR-0026

## Context
Every subscription period needs a record: what was owed, for which period, and which payment
settled it. In Senegal and the UEMOA a legal tax invoice carries the seller's NINEA, VAT and
legal numbering; Yoon knows none of that and must not pretend to.

## Decision

### Invoices are billing records, not tax invoices
`in_…`, per application: `number`, `customer`, `subscription`, `lines` (description, period,
price, amount), `amount_due`, `currency`, `status`, `period_start`, `period_end`, `payment`
(the hosted payment that settles it), `paid_at`, `voided_at`, `created_at`.

The docs and the receipt say plainly: **"Payment receipt — not a tax invoice."** Merchants who
must issue tax invoices produce them from the `invoice.paid` event in their own system.

### Numbers
Per application, gap-free: `<PREFIX>-<YYYY>-<000001>` (prefix per application, default `INV`;
ADR-0026 Settings). The counter is a row per application locked (`SELECT … FOR UPDATE`) in the
transaction that creates the invoice, so numbers never repeat or skip — even with several
instances.

### Statuses
`open` → `paid` | `void` | `uncollectible`.
- `paid` only through a confirmed payment of the exact `amount_due` (`Settlement` already refuses
  mismatched amounts; a mismatch leaves the invoice open and raises an alert).
- `void`: a cancelled subscription's open invoice, or one replaced by a plan change.
- `uncollectible`: reserved for dunning (0.5.0); not set yet.
- A payment confirmed **after** `void` still marks it paid and applies (money received is never
  ignored), with an alert for a human.

An invoice is paid through an ordinary hosted payment, which keeps the normal checkout TTL
(`YOON_CHECKOUT_TTL`, 30 minutes by default). The invoice itself stays open: when its checkout has
expired, `renew` gives it a fresh hosted payment (same invoice, same number). An incomplete
subscription whose first invoice stays unpaid for the application's invoice TTL (default 7 days)
expires (ADR-0026).

### Receipts
`GET /checkout/receipts/{invoice id}?t=<token>` on the hosted-checkout pages (data from
`GET /checkout/api/receipts/{id}` with the token in the `Yoon-Checkout-Token` header): a static HTML receipt,
FR/EN (the browser's languages + toggle), printable (print stylesheet), same rules as the checkout page
(strict CSP, `textContent` only, no browser storage, token-scoped, identical 404s). The invoice
response carries `receipt_url` once paid. PDF is deferred to 0.4.0 (it would add a rendering
dependency to the server image; "Print → Save as PDF" covers it meanwhile).

### Events
`invoice.created`, `invoice.paid`, `invoice.voided` — open-set event types as in ADR-0026.

## Consequences
- One more counter table and a unique `(application_id, number)` index.
- Merchants needing tax invoices keep generating them; Yoon gives them the paid record and event.
