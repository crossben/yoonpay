# Yoon

> *Yoon* (Wolof): the way, the road. Yoon picks the way a payment travels.

**A self-hosted, open-source payment gateway for Africa's payment providers.**
One API in front of PayDunya, DexPay and NabooPay (more later): routing,
verified webhooks, idempotency, a ledger and automatic reconciliation — written
once, in Java, instead of in every project.

> **Status: early development.** The HTTP API works end to end, but no real
> provider is wired in yet (PayDunya, DexPay and NabooPay come next), and provider
> webhooks and automatic reconciliation are still being built. Not for production.

## What works today

- **Money** as integer minor units per currency (5 000 XOF is `5000`). No
  floating point anywhere.
- **State machines** for payments, payouts and refunds. Every status change is
  checked: a late "failed" after a success is ignored; a confirmed success after
  an expiry is recovered; an illegal change is an error, never a silent overwrite.
- **Three outcomes per provider call** — accepted, rejected, unknown. A timeout
  is *unknown*, never *failed*, so Yoon will not fail over or retry a payment the
  provider may already be processing (no double charge, no double payout).
- **Shadow ledger** (double entry) in Postgres. The database itself refuses an
  unbalanced posting and any edit or deletion — tested against a real Postgres.
- **Idempotency store**: the same request sent twice, or twenty times at once,
  runs exactly once.
- **FakeProvider** (`yoon-testkit`): a scriptable provider that accepts, rejects,
  hangs, goes down, times out after accepting, reports partial payments and sends
  duplicate, late or forged webhooks.
- **HTTP API** with per-application API keys, documented at `/docs`:
  - payments, full and partial refunds, payouts;
  - **routing**: you send intent (amount, country, method); Yoon picks the
    configured provider by capability, availability and priority, or the one you
    pin;
  - **failover only after a definite refusal** — never after a timeout — and
    never for payouts;
  - a circuit breaker per provider takes a failing provider out of routing;
  - search, cursor pagination, status history of every payment/refund/payout,
    balances and ledger entries, CSV exports for accounting;
  - errors as `application/problem+json` with stable codes.
- **Documentation that cannot drift**: tests fail if a route is missing from
  `api/openapi.yaml` or if any response does not match it.

Coming next: provider webhooks and automatic reconciliation, then PayDunya,
DexPay and NabooPay.

## What Yoon is not

- **Not an aggregator.** You open your own merchant account with each provider
  and bring your own keys. Yoon removes the integration work, not the onboarding.
- **Not a card vault.** Yoon never sees card numbers; cards go through the
  provider's hosted checkout.
- **Not a checkout UI.** Yoon returns the provider's checkout URL or push/USSD
  instruction; your app renders its own UI.
- **Not a hosted service.** You run it yourself. It sends no telemetry.

## Run locally

Requirements: Docker. To build from source: JDK 25.

```sh
cp .env.example .env                               # set POSTGRES_PASSWORD
docker compose up --build -d
docker compose run --rm yoon apps create shop      # prints the API key once
```

Then:

- API documentation (Swagger UI): <http://localhost:8080/docs>
- Health: `curl localhost:8080/actuator/health`
- A first call:

```sh
curl -X POST localhost:8080/v1/payments \
  -H "Authorization: Bearer yk_…" \
  -H "Idempotency-Key: $(uuidgen)" \
  -H "Content-Type: application/json" \
  -d '{"amount":5000,"currency":"XOF","country":"SN","method":"wave","customer":{"phone":"+221771234567"}}'
```

Until a provider is configured for the application this answers `422
no_provider_for_method` — that is the routing working.

Build and test (needs a running Docker daemon — integration tests start a real Postgres):

```sh
./mvnw verify
```

## Configuration

| Variable | Required | Meaning |
| --- | --- | --- |
| `YOON_DB_URL` | yes | JDBC URL, e.g. `jdbc:postgresql://localhost:5432/yoon` |
| `YOON_DB_USER` | yes | Database user |
| `YOON_DB_PASSWORD` | yes | Database password |
| `YOON_PUBLIC_URL` | for webhooks | Public HTTPS address of this instance; providers call back to `<url>/v1/hooks/…` |
| `YOON_SOURCE_URL` | if modified | Where users get this instance's source code (AGPL-3.0). Defaults to the upstream repository; set it if you run a modified version |
| `YOON_APPS_<APP>_PROVIDERS_<PROVIDER>_PRIORITY` | per provider | Enables a provider for an application; lower is preferred (default 100) |
| `YOON_APPS_<APP>_PROVIDERS_<PROVIDER>_CREDENTIALS_<KEY>` | per provider | That application's merchant credentials for the provider. Never logged |

`<APP>` is the application name given to `apps create`, upper-cased with `-`
turned into `_`. Provider settings are read at startup: restart after changes.

## Licence

Server, core and providers: [AGPL-3.0](LICENSE). Client libraries: Apache-2.0.
A commercial licence is available for companies that cannot use AGPL.

## Project layout

| Module | Contents |
| --- | --- |
| `yoon-core` | Domain: money, state machines, ledger model, provider interface. Pure Java, no dependencies. |
| `yoon-testkit` | `FakeProvider` and test helpers. |
| `yoon-server` | The Spring Boot gateway: HTTP API, database; webhooks and scheduler next. |
| `api/openapi.yaml` | The API contract, written by hand; served at `/openapi.yaml` and rendered at `/docs`. |
| `docs/adr/` | Architecture decisions and why they were made. |

Contributors and AI agents: read [CLAUDE.md](CLAUDE.md).
