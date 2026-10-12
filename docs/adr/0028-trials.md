# ADR-0028 — Free trials

- Status: proposed
- Date: 2026-10-12
- Builds on: ADR-0026 (subscriptions), ADR-0027 (invoices)

## Context
A SaaS lets people try before paying. With prepaid mobile money there is nothing to "charge at
the end of the trial": the customer has to come back and pay. The trial must therefore end the
same way an unpaid renewal does, so merchants handle one flow.

## Decision

### Where the trial comes from
- `Plan.trial_days` (0–90, default 0, PATCHable): the plan's default trial.
- `POST /v1/subscriptions` takes `trial_days` (0–90) to override it for this subscription; `0`
  means no trial.
- **One trial per customer and plan:** if the customer already had a subscription to this plan
  with a trial, a new one gets no trial (default) or, when `trial_days > 0` is asked explicitly,
  `409 trial_already_used`. Merchants who want to grant another trial use a comp (ADR-0026).

### Status `trialing`
A new status. The subscription starts `trialing` instead of `incomplete`: no invoice and no
payment at creation. `current_period_start` = now, `current_period_end` = `trial_end` = now +
`trial_days` (calendar days in UTC). **Entitled.**

| From `trialing` to | When |
| --- | --- |
| `active` | an invoice of this subscription is paid during the trial |
| `past_due` | the trial ends unpaid (the billing sweep opens the first invoice) |
| `canceled` | canceled now, or at the trial end with `cancel_at_period_end` |

`expired` is reached only through `past_due`, after the grace days (ADR-0026), exactly as for an
unpaid renewal. A late payment re-activates, as everywhere.

### Paying during the trial
`POST /v1/subscriptions/{id}/renew` works while `trialing`: it opens the first invoice (kind
`first`) with its checkout. When it is paid, the subscription becomes `active` and the paid
period **starts at the trial end** (the customer keeps the trial days). Stacking rule added to
`Stacking.nextPeriodStart`: `TRIALING → current_period_end`.

### Sweep and events
The billing sweep (ADR-0026) treats the trial end like a period end: a reminder
(`subscription.trial_will_end`, once, the application's reminder days before), then at the end
the first invoice is opened and the subscription goes `past_due` (or `canceled` with
`cancel_at_period_end`). New event type `subscription.trial_will_end`; `subscription.created`
carries the `trialing` subscription.

### Response enums
`trialing` is a new value of `SubscriptionStatus` (open set, ADR-0026 "Contract"). **Clients must
be upgraded before a merchant enables trials**: the 0.3 helpers treat an unknown status as not
entitled, so a trial user would be denied access. The 0.4 helpers count `trialing` as entitled.

## Consequences
- No migration of existing subscriptions; `trial_end` and `plans.trial_days` are new columns.
- No money moves during a trial; no ledger change.
- Dashboard and exports show `trialing` and `trial_end`.
