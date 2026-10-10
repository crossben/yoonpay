# ADR-0025 — Customers, plans and prices

- Status: proposed
- Date: 2026-10-10
- Part of: billing for 0.3.0 (with ADR-0026 subscriptions, ADR-0027 invoices)

## Context
Merchants that sell subscriptions (first: OSIG, a FastAPI SaaS) rebuild plans, periods, renewals
and expiry on top of one-off `/v1/payments`. Yoon has no notion of a customer or a product: a
payment carries a phone number and a free-text reference, nothing more. Subscriptions need both.

## Decision

### Customers
A new resource, per application: `cus_…`.

| Field | Notes |
| --- | --- |
| `external_id` | The merchant's own user id. Unique per application; the way merchants find their customers (`GET /v1/customers?external_id=`). Required. |
| `email` | Optional. Stored as given; returned in full to the owning application (the merchant gave it), never logged. |
| `phone` | Optional, E.164 like payments (`Phones.normalize`), **returned masked**. |
| `pi_alias` | Optional, as on payments. |
| `country` | ISO 3166 alpha-2. Default country for the customer's payments. |
| `name`, `metadata` | Optional. `metadata`: up to 20 string keys/values (≤ 40 / ≤ 500 chars). |

`POST /v1/customers` (idempotent), `GET /v1/customers/{id}`, `GET /v1/customers?external_id=`,
`PATCH /v1/customers/{id}`. No delete in 0.3.0: a customer with billing history must stay
readable; erasing personal data is a later, explicit operation (`email`/`phone`/`name` blanked,
id and history kept) — noted in the security checklist.

### Plans and prices
A **plan** is what the customer buys (`plan_…`): `slug` (unique per application), `name`,
`active`, `features` (free-form JSON returned on subscriptions, for entitlements), `metadata`.

A **price** is how much and how often (`price_…`), attached to one plan:
`currency`, `amount` (integer minor units, > 0), `interval` (`day`, `week`, `month`, `year`),
`interval_count` (≥ 1; a quarter is `month` × 3, a half-year `month` × 6), `active`.

- **Prices are immutable.** Changing an amount means creating a new price and archiving the old
  one (`active: false`). Subscriptions on an archived price keep it until they move (ADR-0026).
- **Nothing is deleted.** Plans and prices are archived; archived ones cannot start new
  subscriptions but stay readable.
- A plan may have several active prices (one per currency × interval). A yearly price is just a
  price: there is no "N periods free" concept; a discounted year is a lower yearly amount.
  Coupons and discounts are out of scope.

Endpoints: `POST/GET/PATCH /v1/plans`, `GET /v1/plans/{id}`, `POST /v1/plans/{id}/prices`,
`PATCH /v1/prices/{id}` (only `active`), `GET /v1/prices/{id}`. Writes are idempotent like every
write (`Idempotency-Key`).

### Money
Integer minor units everywhere, `Money` in core, no floats. A price's currency is fixed; a
subscription's currency is its price's.

## Consequences
- Customers bring a new kind of personal data (email); the security checklist and
  `docs/runbook.md` gain a section on it.
- Payments may now reference a customer (`customer_id`, additive); one-off payments without a
  customer are unchanged.
