# ADR-0026 — Subscriptions, prepaid periods and their lifecycle

- Status: proposed
- Date: 2026-10-10
- Builds on: ADR-0024 (hosted checkout), ADR-0025 (customers, plans, prices). Invoices: ADR-0027.

## Context
In West Africa most customers pay by mobile money, which has no card-style auto-debit: every
period is a payment the customer makes. Merchants therefore sell **prepaid periods** and need
renewals, reminders, grace, expiry and plan changes. Automatic charging (saved cards, mandates)
needs a new provider operation and only Stripe offers it today: it is **not** in 0.3.0 (planned
for 0.4.0 with dunning, behind a provider capability and its own ADR).

## Decision

### Resource
`sub_…`, per application: `customer`, `price` (and its `plan`), `status`, `collection_method`
(only `prepaid_manual` in 0.3.0; the field exists so `automatic` can be added),
`current_period_start`, `current_period_end` (null only for an open-ended comp, see Operator),
`grace_period_days` (default from the application's setting, see Settings), `cancel_at_period_end`, `pending_change`
(`{price, effective_at}` or null), `canceled_at`, `features` (copied from the plan at read time),
`comp` (true for operator-granted periods), `metadata`.

### Statuses
| Status | Meaning | Entitled? |
| --- | --- | --- |
| `incomplete` | Created; its first invoice is not paid yet. | no |
| `active` | Paid up to `current_period_end`. | yes |
| `past_due` | Period ended, renewal not paid, still within `grace_period_days`. | **yes** (merchant may choose otherwise; documented) |
| `expired` | Grace ended unpaid, or an `incomplete` subscription's first invoice expired. | no |
| `canceled` | Ended on request (immediately, or at period end with `cancel_at_period_end`). | no |

Transitions go through a `SubscriptionStatus` state machine in core (APPLY / IGNORE / throw, the
full matrix tested) and a `SubscriptionTransitions` class — the only code that changes a
subscription — which locks the row, records `status_events`, applies, and writes outbound events
in one transaction, exactly like `PaymentTransitions`. `expired` and `canceled` are final except
that **a confirmed payment of an open invoice still applies** (money received must not be lost):
it re-activates the subscription from the payment time and emits `subscription.activated`.

### Paying for a period
- Creating a subscription creates its first invoice (ADR-0027) and a **hosted-checkout payment**
  for it; the response carries `latest_invoice` with `checkout_url`. Nothing is activated by the
  return URL: only `payment.succeeded`, inside `PaymentTransitions`' transaction, marks the
  invoice paid and calls `SubscriptionTransitions` (same transaction).
- `POST /v1/subscriptions/{id}/renew` (idempotent) creates the next period's invoice + checkout,
  or returns the one already open: **at most one open invoice per subscription** (unique partial
  index). The customer may renew any time while `active`, `past_due` or `expired`.

### Stacking (when does a paid period start?)
On payment of a renewal invoice at time `t`:
- `active` → new period starts at `current_period_end` (paying early never loses days).
- `past_due` → new period starts at the **old** `current_period_end`: grace days are not free.
- `expired`, `canceled`, `incomplete` → new period starts at `t`.
The period length comes from the price, with calendar arithmetic anchored on the subscription's
`billing_anchor` (the day-of-month of the first period start): a period anchored on the 31st
renews on Feb 28/29, then on the 31st again. Timestamps are UTC. "Days before" in reminders are
counted in the application's time zone (default `Africa/Dakar`, see Settings).

### Plan changes (`POST /v1/subscriptions/{id}/change` {price, when})
- **Downgrade or same-price change** (new price amount per day ≤ current): scheduled,
  `pending_change` set, applied at `current_period_end` (the next renewal invoice uses it).
  `when: "now"` is refused for these (400): nothing to charge.
- **Upgrade** (`when: "now"`, default for upgrades): an invoice for one full period of the new
  price, paid by hosted checkout. On payment the new period starts at the payment time, and the
  **unused time of the old period is converted into extra days** on the new period:
  `extra_days = floor(remaining_seconds × old_amount_per_second / new_amount_per_second / 86400)`,
  computed with integers (no money proration, no refunds — mobile money cannot pay back
  fractions). Until the upgrade invoice is paid, nothing changes.
- Changing currency is refused (a new subscription is needed).
- **Renewing with a change pending** (e.g. Pro monthly → Pro yearly, which is not an upgrade per
  unit of time): the renewal invoice is at the **pending** price; when it is paid, the change is
  applied and the period **stacks** as usual (starts at `current_period_end`). This is how a
  customer "buys the yearly plan now" without losing the month they already paid — OSIG's
  behaviour today.

### Cancel (`POST /v1/subscriptions/{id}/cancel` {at_period_end = true})
- Default: `cancel_at_period_end = true`; at `current_period_end` the subscription becomes
  `canceled` (not `past_due`), and no renewal is offered. Reversible until then with
  `POST /v1/subscriptions/{id}/resume`, which clears `cancel_at_period_end`.
- Immediate cancel ends entitlement now. **No automatic refund**: a refund is a deliberate
  `POST /v1/payments/{id}/refunds`, as today. Any open invoice is voided.

### Lifecycle jobs
One `BillingSweep` worker, every minute under ShedLock (`sweep-billing`) like the reconciler. It
selects candidate rows, then handles each in its own transaction: lock the subscription, re-check
the condition under the lock (a payment may have settled meanwhile), transition through
`SubscriptionTransitions`. A failing row is counted in `yoon.billing.sweep.errors` and retried next pass:
1. **Reminders:** `subscription.renewal_upcoming` once per period, the application's reminder days before `current_period_end` (a `reminded_period_end` column makes it once).
2. **Period end:** `active` at `current_period_end` → `canceled` if `cancel_at_period_end`,
   else apply `pending_change` (it becomes the price) and → `past_due`, creating the renewal
   invoice if none is open (so the customer can always pay).
3. **Grace end:** `past_due` after `grace_period_days` → `expired`; open invoice kept open
   (a late payment still re-activates, see Statuses).
4. **Incomplete expiry:** `incomplete` whose first invoice expired → `expired`.
The jobs read time from an injected `Clock` so tests move time instead of sleeping.

### Events (outbox, existing signing scheme, full object in `data.object`)
`subscription.created`, `subscription.activated` (incomplete/expired/canceled → active),
`subscription.renewed` (a paid period added to an active/past_due subscription),
`subscription.past_due`, `subscription.expired`, `subscription.canceled`,
`subscription.changed` (price or pending change set/applied/cleared),
`subscription.renewal_upcoming`. Merchants can drive entitlements from these alone; they must
still reconcile with `GET /v1/subscriptions?customer=` after an outage (documented).

**Contract — response enums are an open set.** Adding a value to `EventType` (or any response
enum) is reported by `breaking-changes` CI as `response-property-enum-value-added`. Checked
options: `x-extensible-enum` is itself breaking (the `type` filter of `GET /v1/events` loses its
values), so instead the policy is that **a new value in a response enum is not a breaking change**,
and every client must parse values it does not know. Found while checking: the 0.2.0 Java and PHP
clients **threw** on an unknown status or event type. Fixed in `generate.sh` (PHP: no enum check
when parsing; Java: `enumUnknownDefaultCase` → `UNKNOWN_DEFAULT_OPEN_API`; Python already kept the
raw value; JS has no runtime checks), with a test in each client. CI lowers that one rule to info
(`api/oasdiff/severity-levels.txt`); removing a value, or adding one to a request, still fails.

### Operator
- `yoon billing comp <app> <sub_… | cus_…> <price> <periods | open> <note>` (CLI) and
  `POST /admin/v1/applications/{app}/subscriptions/{id}/comp` (or `/customers/{id}/comp`, which
  creates the subscription), with an `Idempotency-Key`: adds periods with **no payment**, stacked
  like a renewal, `comp: true`, recorded in `status_events` with cause `admin` and the operator's
  note; an open invoice is voided. No ledger posting (no money moved). `open` grants without an
  end date (`current_period_end` null: sweeps skip it) until the subscription is canceled — for
  "contact us" plans. A later paid period sets `comp` back to false.
- Dashboard: plans, subscriptions (with history) and invoices tabs, read-only (comp stays in the
  CLI and the operator API, like resolving a payout).
- CSV exports: `subscriptions.csv`, `invoices.csv`.

## Consequences
- No ledger change: postings still come only from payments. Yoon does no revenue recognition or
  deferred revenue; its ledger mirrors money held at providers (ADR-0012).
- `/v1/payments` unchanged; subscription payments are ordinary hosted payments with a
  `subscription` and `invoice` reference (additive fields).
- Yoon sends no e-mail: reminders are events the merchant turns into messages.
- New settings, **per application** (several applications share one Yoon):
  `YOON_APPS_<APP>_BILLING_GRACE_DAYS` (default 3), `YOON_APPS_<APP>_BILLING_REMINDER_DAYS`
  (default 3), `YOON_APPS_<APP>_BILLING_TIMEZONE` (default `Africa/Dakar`),
  `YOON_APPS_<APP>_BILLING_INVOICE_PREFIX` (default `INV`), `YOON_APPS_<APP>_BILLING_INVOICE_TTL`
  (default `7d`).
- Deferred to 0.4.0: `automatic` collection and dunning (provider capability, Stripe first),
  customer portal, PDF receipts.
