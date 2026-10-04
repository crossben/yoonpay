# ADR-0024 — Hosted checkout: the customer picks the payment method on a Yoon page

- Status: accepted
- Date: 2026-10-04
- Builds on: ADR-0009 (contract first), ADR-0010 (routing and failover), ADR-0021 (static page, strict CSP)

## Context
`POST /v1/payments` needs `method` up front. Every shop therefore rebuilds the same screen:
"Wave, Orange Money, Free Money, card or PI-SPI?", and must know which methods its configured
providers actually support for the customer's country and currency. Yoon already knows that
(capabilities, priorities, circuit breakers).

## Decision

### API (additive to `/v1`)
- `CreatePaymentRequest` gains `checkout`: `direct` (default, today's behaviour) or `hosted`.
- With `checkout: "hosted"`, `method` is optional. If given, the page offers only that method.
  `provider` (pinning) cannot be combined with `hosted` (400). Without `YOON_PUBLIC_URL` a hosted
  payment cannot be created (422 `public_url_required`): the page URL must be absolute.
  At least one configured provider must collect in that country and currency (422
  `no_provider_for_method`, as today).
- A hosted payment is created in status `created`, with **no provider call**. The response adds
  `checkout` (`direct`/`hosted`) and `checkout_expires_at`; for hosted payments `checkout_url` is
  the Yoon page, `{YOON_PUBLIC_URL}/checkout/{id}?t={token}` — "where to send the customer", as the
  field always meant. `method` is `any` until the customer chooses. It stays a non-null string because
  `breaking-changes` CI compares the contract with 0.1.0, whose clients expect one.
- From then on the payment is an ordinary payment: same statuses, events, webhooks, refunds.

### Public checkout endpoints (no API key; tag `Hosted checkout`, not in the client libraries)
- `GET /checkout/{id}?t=…` — the static page (HTML + one vanilla ES module + CSS, as ADR-0021).
- `GET /checkout/api/{id}` — the page's view of the payment: amount, currency, description, status,
  the methods available, the next action (provider URL or push instructions), the masked phone,
  return URL. Nothing else: no app name, ids of other objects, provider references, routing notes
  or secrets.
- `POST /checkout/api/{id}/attempts` `{method, phone?, pi_alias?}` — the customer's choice.
- The token travels in the `Yoon-Checkout-Token` header on the API calls. A missing or wrong token,
  or an unknown id, is the same `404 resource_not_found` (no oracle). Tokens are 32 random bytes
  (base64url), compared in constant time, and grant only this payment's checkout: read its view,
  start an attempt while it is `created`.

### Money safety
- **One attempt round at a time.** Choosing a method first claims the payment with one conditional
  `UPDATE … SET checkout_busy = true WHERE status = 'CREATED' AND NOT checkout_busy AND
  checkout_expires_at > now()`, committed on its own before any provider call (the same pattern as
  `IdempotencyStore.begin`). A second click, a second tab or a replayed request finds the claim
  taken (409 `checkout_in_progress`) or the payment no longer `created` (409
  `checkout_not_open`). No provider call happens without the claim.
- The round runs the existing routing and failover code (`PaymentService.attempt`, shared with
  direct payments): next provider only after `Rejected`.
  - `Accepted` → `pending` through `PaymentTransitions`; the page redirects to the provider's URL
    or shows its instructions.
  - `Unknown` → `pending`, as for direct payments. The status is no longer `created`, so the page
    cannot start another attempt: it shows "confirming" and polls.
  - every provider `Rejected` → the claim is released and the payment **stays `created`**: the
    customer may pick another method (or enter the phone / PI alias the provider asked for). After
    10 attempts in total the payment becomes `failed` (`checkout_attempts_exhausted`).
- A process crash mid-round leaves the claim taken. The reconciler treats it as today's
  "interrupted creation": an attempt whose outcome was never stored → `pending` (unknown), else the
  claim is released.
- Status changes go only through `PaymentTransitions`; the claim and the customer's choice
  (`method`, phone, alias) are plain columns, never the status.
- Expiry: `YOON_CHECKOUT_TTL` (default 30 minutes) after creation, an unclaimed `created` hosted
  payment becomes `failed` with code `checkout_expired` (the state machine allows
  `created → failed`; `created → expired` would need a core change and is not needed: `failed`
  still accepts a later confirmed success). The reconciler sweep does it; the page does it on read
  too. The claim refuses an expired payment.
- The provider's `return_url` for a hosted payment is the Yoon page (with its token), so the
  customer comes back to Yoon, sees the confirmed status, then follows the application's
  `return_url`.

### Page
- Same rules as the dashboard (ADR-0021): static assets, no build, no CDN, data written with
  `textContent` only, strict CSP (`default-src 'none'; script-src 'self'; style-src 'self';
  img-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors
  'none'`), `no-store`, `Referrer-Policy: no-referrer` (the token is in the page URL).
- Phone-width first, light/dark from `prefers-color-scheme`, French or English from the browser
  language with a toggle, labelled controls, live region for status changes.
- Navigation targets (provider URL, return URL) are followed only if `http(s)`.

### Abuse
- The public API is cheap: one primary-key read per status poll, a provider call only under the
  claim, at most 10 attempts per payment. A per-client-address fixed-window limit
  (`YOON_CHECKOUT_RATE_LIMIT`, requests per minute, default 300, 0 = off) answers 429 beyond it.
  Behind a reverse proxy the client address comes from `X-Forwarded-For`, trusted only from
  private-network proxies (`server.forward-headers-strategy=native`).

## Consequences
- Applications can skip building a method picker; `direct` payments are untouched.
- `method` is `any` for a hosted payment whose customer has not chosen yet; clients built against
  0.1.0 keep working.
- The token is stored in clear in `payments` (it must be returned on every `GET`). Read access to
  the database lets someone open a customer's checkout, which moves no money to them.
- The token reaches the provider inside the return URL. It still grants only this checkout.
- The rate limit is per instance (in memory); several instances each apply it.
