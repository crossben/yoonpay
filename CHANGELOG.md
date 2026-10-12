# Changelog

All notable changes. Versions follow [semantic versioning](https://semver.org); `/v1` of the API
never changes incompatibly.

## [Unreleased]

### Trials and coupons (0.4.0, in progress)
- Coupons: `POST/GET /v1/coupons`, `GET/PATCH /v1/coupons/{id}` (ADR-0029): percent or fixed
  amount off, for one invoice, N invoices or forever; optional cap, deadline and plan list. Codes
  are unique per application whatever the case. Terms never change (a database trigger refuses
  it); archive with `active: false`; the cap can only be raised or removed.
- Discounts: `coupon` on `POST /v1/subscriptions`, `POST/DELETE /v1/subscriptions/{id}/discount`.
  Invoices gain `subtotal`, `discount_amount`, `discount`; subscriptions `discount` (with
  `periods_remaining`). A use is consumed when an invoice is paid, so a voided invoice never
  costs one. Refusals are `422 coupon_not_applicable` with a `reason` (`unknown`, `archived`,
  `expired`, `exhausted`, `plan`, `currency`). The cap holds under concurrent redemptions.
- **100% off**: an invoice with nothing to pay is paid at creation, without a payment or ledger
  posting; the subscription is activated or renewed as usual and the receipt says which coupon
  paid it. The checkout and the receipt show the coupon and the discount.
- Free trials (ADR-0028): `Plan.trial_days` (default trial) and `trial_days` on
  `POST /v1/subscriptions`. A trial starts `trialing` (entitled), with nothing to pay and no
  invoice; one trial per customer and plan (`409 trial_already_used` when asked again). Paying
  during the trial (`/renew`) starts the paid period at the trial end. Unpaid at the end: the
  first invoice opens and the subscription goes `past_due` (grace), then `expired`. Cancel at the
  trial end works; a plan change during the trial only switches the price. New event
  `subscription.trial_will_end` (reminder days before), field `Subscription.trial_end`.
- **Upgrade your clients before using trials.** The 0.4 Python and JS helpers count `trialing`
  as entitled and treat `subscription.created` as an entitlement event (a subscription may now
  start entitled); the 0.3 helpers would deny access to trial users.
- Migration `V8` (trials and coupons schema). Invoices gain `subtotal` (equal to `amount_due`
  until discounts apply).

## [0.3.1] - 2026-10-12

Found by the first runs against real provider APIs (DexPay sandbox, NabooPay live). Upgrade if
you set provider credentials with environment variables.

### Fixed
- **Provider credentials with a multi-word name set by environment variable were not found**
  (`…_CREDENTIALS_API_KEY`, as every provider page documents): Spring passes them as `api.key`,
  which did not match `api-key`. The provider failed on its first use. Every version since 0.1.0
  was affected for these variables; YAML or `api-key`-style names worked. Credential names now
  ignore `.`, `-`, `_` and case.
- A misconfigured provider (missing credential, unknown provider name) now stops Yoon at startup
  with one message listing every problem (no credential value printed), instead of failing the
  first request that routes to it with a 500. An unknown provider name was silently ignored.
- Opening an API or webhook URL with the wrong method answers **405** with `Allow` (was 500), e.g.
  a provider dashboard checking a webhook URL with GET. A wrong content type answers 415, a
  missing query parameter 400.

### Providers
- DexPay run end to end against its sandbox, NabooPay against its live API (small payments):
  payment, real signed callback, status confirmation. What was observed is in each provider page;
  the recorded callback shapes are now unit tests.

## [0.3.0] - 2026-10-11

Prepaid subscriptions for mobile money. Upgrading from 0.2.0: migration `V7` runs on start-up.
`/v1` stays compatible: new routes and fields only. Upgrade your clients before you subscribe a
webhook endpoint to billing events: the 0.2.0 Java and PHP clients fail on event types they do
not know (see Clients).

### Billing
- Customers: `POST/GET/PATCH /v1/customers`, found by your own `external_id` (unique per
  application; a duplicate is `409 customer_exists`). Phones masked; `metadata` up to 20 strings.
- Plans and prices: `POST/GET/PATCH /v1/plans`, `POST /v1/plans/{id}/prices`,
  `GET/PATCH /v1/prices/{id}`. Prices are immutable (a database trigger refuses edits); plans and
  prices are archived with `active: false`, never deleted. Plans carry free-form `features`.
- Subscriptions: `POST/GET /v1/subscriptions`, `/renew`, `/change`, `/cancel`, `/resume`,
  `/events`; invoices: `GET /v1/invoices`. Each period is an invoice paid through the hosted
  checkout; the payment's confirmation, the paid invoice and the extended subscription commit in
  one transaction. Stacking, upgrades with unused time as extra days, scheduled downgrades,
  cancel at period end or now (open invoice voided; a late payment still counts, with an alert).
- Events: `subscription.created|activated|renewed|past_due|expired|canceled|changed|renewal_upcoming`,
  `invoice.created|paid|voided`.
- Billing sweep, every minute under ShedLock: `subscription.renewal_upcoming` once per period
  (reminder days before the end, counted in the application's time zone); at period end
  `cancel_at_period_end` → `canceled`, otherwise a pending change is applied and the subscription
  becomes `past_due` with a renewal invoice; after grace → `expired` (the invoice stays payable);
  an unpaid first invoice older than the invoice TTL → `expired`. Each row is re-checked under its
  lock; failures count in `yoon.billing.sweep.errors`.
- Receipts: a paid invoice's `receipt_url` opens a printable payment receipt (French/English,
  "not a tax invoice") at `/checkout/receipts/{id}?t=…`, under the checkout page's rules (strict
  CSP, no cache, rate limit, the same 404 for every refusal). The checkout of an invoice shows the
  plan, interval and invoice number (`billing` in the checkout view) and links the receipt once paid.
- Comp: `billing comp` (CLI) and `POST /admin/v1/applications/{app}/{subscriptions,customers}/{id}/comp`
  grant periods, time until a date (for migrations) or an open-ended period, without payment or
  ledger posting, with a note in the history (cause `admin`).
- Operator views `/admin/v1/applications/{app}/{plans,subscriptions,invoices}` and
  `subscriptions/{id}/events`; dashboard tabs Subscriptions, Invoices and Plans (read-only).
- CSV exports `GET /v1/exports/subscriptions.csv` and `/v1/exports/invoices.csv`.
- Invoice numbers `<PREFIX>-<year>-<000001>`, gap-free per application.
- Per-application billing settings: `YOON_APPS_<APP>_BILLING_GRACE_DAYS` (3),
  `_REMINDER_DAYS` (3), `_TIMEZONE` (Africa/Dakar), `_INVOICE_PREFIX` (INV), `_INVOICE_TTL` (7d).
- Migration `V7__billing`: all billing tables.
- Ids made in the same millisecond now sort in creation order (RFC 9562 counter), so "oldest
  first" holds for a plan and its prices created together.

### Clients
- **Response enums are an open set.** The 0.2.0 Java and PHP clients failed to parse a status or
  event type they did not know; they now keep going (PHP keeps the raw value, Java maps it to
  `UNKNOWN_DEFAULT_OPEN_API`), like Python and JS. Event payloads pick their object type by its
  `object` field (discriminator). Each client has a test. CI allows new values in response
  enums and still fails on removed ones (`api/oasdiff/`). Upgrade your client before using
  billing events.
- Billing helpers. Python: `Yoon.customers()`, `plans()`, `subscriptions()` (invoices through
  `subscriptions()`); `yoonpay.billing` (`Entitlement.from_subscription()`, `changes_entitlement()`,
  `checkout_url()`); `yoonpay.fastapi.redirect_to_checkout()` (303, `NoCheckout` when there is
  none: renew). JS: `createCustomer`, `createSubscription`, `getSubscription`,
  `renewSubscription`, `customers()`/`plans()`/`subscriptions()`; `entitlementOf()`,
  `changesEntitlement()`, `checkoutUrlOf()`/`NoCheckoutError`; `checkoutRedirect()` in
  `@yoonpay/yoon/next`. `past_due` is entitled; unknown statuses are not, and never throw.
- Guide: [docs/billing.md](docs/billing.md) — Billing for SaaS, with a FastAPI example and
  migrating from your own billing.
- Customers and plans APIs in every client (regenerated).
- Every client README has a "Prompt for an AI coding agent": a copy-ready brief that tells a coding
  agent what to install and write, and the rules that protect money (idempotency keys tied to the
  order, fulfil only on `payment.succeeded`, raw-body webhook verification, minor units).

### Fixed
- Webhook deliveries and provider calls over plain `http://` were refused by servers that reject
  HTTP/2 upgrade requests (uvicorn's default answers `Upgrade: h2c` with 400), so such endpoints
  never received events. Yoon now always speaks HTTP/1.1. Found while integrating OSIG.
- Docs: local webhook setup with Docker Desktop (IPv6 `host.docker.internal`, clock drift);
  every delivery attempt, including redeliveries, is signed with its send time (now tested).

## [0.2.0] - 2026-10-07

Upgrading from 0.1.0: migration `V6` runs on start-up. `/v1` stays compatible: new
fields only, and `method` stays a string (`any` for a hosted checkout not chosen yet).

### API
- `POST /v1/payments` accepts `checkout: "hosted"`: `method` becomes optional, no provider is called,
  and `checkout_url` points to a Yoon-hosted page where the customer picks the method (ADR-0024).
  Until the customer chooses, `method` is `any` (still a string, so 0.1.0 clients keep working).
- Payments gain `checkout` (`direct`/`hosted`) and `checkout_expires_at`.
- New public, token-scoped endpoints for the checkout page: `GET /checkout/api/{id}`,
  `POST /checkout/api/{id}/attempts` (tag "Hosted checkout", not in the client libraries).

### Gateway
- Hosted checkout page at `/checkout/{id}`: static, strict CSP, FR/EN, light/dark, phone width.
  One attempt round at a time (claimed before any provider call); an unknown outcome or an
  accepted attempt ends the choice; refusals let the customer choose again (max 10 attempts).
- Unused hosted checkouts fail with `checkout_expired` after `YOON_CHECKOUT_TTL` (default 30m).
- Public checkout endpoints are rate-limited per client address (`YOON_CHECKOUT_RATE_LIMIT`,
  default 300/min). Yoon now takes the client address from `X-Forwarded-For` sent by private-network
  proxies (`server.forward-headers-strategy=native`).
- Dashboard: responsive — tables become cards on phones and tablets (two per row on tablets), ids
  wrap, tabs scroll in one row; no sideways scrolling from 320 to 1920 px wide.
- Operator dashboard at `/dashboard` (`YOON_DASHBOARD_ENABLED`, default `true`; needs
  `YOON_ADMIN_TOKEN`): applications, payments, refunds, payouts, payouts needing review,
  dead letters with replay, balances, ledger and status history. Static page with a strict CSP.
- Operator API: read-only `GET /admin/v1/applications` and per-application
  `/admin/v1/applications/{application_id}/{payments,refunds,payouts}` (+ `/{id}/events`),
  `/balances` and `/ledger/entries`, same results as the application's own `/v1` routes.

### Providers
- New provider **CinetPay** (`cinetpay`, API v1 as used by CinetPay's 2026 SDKs): mobile-money
  payments and transfers in CI, SN, BF, ML, TG, BJ, NE (XOF), CM (XAF) and GN (GNF), per-country
  credentials; no refund API. Notifications are not trusted (CinetPay does not sign them); the
  reconciliation sweep settles CinetPay transactions. Not yet run against the CinetPay sandbox.
  See `docs/providers/cinetpay.md` and ADR-0023.
- New provider **Stripe** (`stripe`, method `card`): Stripe Checkout for international cards,
  full and partial refunds, idempotency keys on every create, `Stripe-Signature` webhooks; no
  payouts. Currencies limited to those where Stripe's amount unit matches ISO 4217 (XOF, XAF,
  EUR, USD, GBP, CAD, CHF). Not yet run against a Stripe test account. See
  `docs/providers/stripe.md` and ADR-0022.
- Every adapter now passes a shared money-safety suite, `ProviderContract` (`yoon-testkit`):
  refused connection → rejected; 5xx, dropped connection or timeout after sending → unknown;
  no guessed statuses; unoffered operations send nothing; forged callbacks are invalid.

### Clients
- All clients are published: `yoonpay/yoon-php` (Packagist), `io.github.crossben:yoon-java` and
  `io.github.crossben:yoon-spring-boot-starter` (Maven Central), `@yoonpay/yoon` (npm),
  `yoonpay` (PyPI). The JS client is published with npm trusted publishing (no token).
- Regenerated for the new API fields (`checkout`, `checkout_expires_at`, operator read models).

## [0.1.0] - 2026-10-01

First release.

### Gateway
- One API for collections, full and partial refunds, and payouts; routing by capability,
  availability and priority; failover only after a definite refusal, never after a timeout;
  payouts never retried or failed over.
- Idempotency on every write; problem+json errors with stable codes; cursor pagination; status
  history; balances, ledger entries and CSV exports; per-application API keys.
- Shadow double-entry ledger whose balance is enforced by Postgres.
- Provider callbacks stored, verified and re-confirmed with the provider's status API; signed,
  retried events to applications with dead letters; automatic reconciliation.
- Operator API (`/admin/v1`), operator CLI (`apps create | list | add-key | revoke-key`).
- A customer or payout recipient can be identified by phone or by PI-SPI payment alias
  (`customer.pi_alias`, `recipient.pi_alias`).

### Providers
- PayDunya (collect, payout), DexPay (collect, payout), NabooPay (collect), and a demo provider.
- **Wave (direct)** (`wave`, Wave Business API): checkout sessions, payouts with idempotency
  keys, full-amount refunds, signed webhooks; SN, CI, ML, BF in XOF. Same method `wave` as the
  aggregators, so routing fails over between them. See `docs/providers/wave.md`, ADR-0020.
- **PI-SPI** (`pispi`, BCEAO instant payments, through the merchant's institution's API
  Business): payment requests to a customer's PI alias, payouts to a PI alias, full-amount
  refunds (returns of funds), OAuth2 client credentials and mutual TLS; the eight UEMOA
  countries in XOF. See `docs/providers/pispi.md`, ADR-0019.

### Clients
- PHP `yoonpay/yoon-php` (PHP 8.2+): Laravel 10–13 (facade, `yoon.webhook` middleware) and a
  Symfony bundle (`Yoon\Symfony\YoonBundle`, `#[YoonWebhook]`; Symfony 6.4 LTS and 7.x).
- Java `io.github.crossben:yoon-java` (Java 17+) and a Spring Boot starter
  `io.github.crossben:yoon-spring-boot-starter` (Spring Boot 3.x and 4.x).
- JavaScript/TypeScript `@yoonpay/yoon` (Node ≥ 20, also Bun, Deno and edge runtimes), with
  adapters for Express, Fastify, NestJS and Next.js.
- Python `yoonpay` (Python 3.10+), with webhook helpers for Django, FastAPI and Flask.
- All generated from the API contract with a thin hand-written layer: an explicit idempotency
  key on every write, no retries, one exception type carrying Yoon's error `code`, and webhook
  signature verification checked against a shared test vector.

### Operations
- Docker image with healthcheck (amd64, arm64), production Compose with Caddy, backups,
  Prometheus alert rules and a Grafana dashboard; runbook; published load-test numbers.

### Known limitations
- The provider adapters are tested against simulated APIs, not against the providers'
  sandboxes: PayDunya, DexPay and NabooPay from production integrations, Wave from its public
  documentation, PI-SPI from the BCEAO specification. Test with your own sandbox keys before
  taking real payments.
- PayDunya, DexPay and NabooPay: Senegal (XOF) only and no refund API (refund by payout). Wave
  and PI-SPI refund the full amount only; send a partial refund as a payout.
- Provider settings are read at startup (restart to apply changes).
