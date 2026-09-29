# Yoon

> *Yoon* (Wolof): the way, the road. Yoon picks the way a payment travels.

**A self-hosted, open-source payment gateway for Africa's payment providers.**
One API in front of PayDunya, DexPay and NabooPay (more later): routing,
verified webhooks, idempotency, a ledger and automatic reconciliation — written
once, in Java, instead of in every project.

> **Status: early development.** Not usable yet — there is no public API and no
> real provider. What exists today is the core domain, proven by tests (below).

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

Coming next: the public HTTP API with API keys and routing, then provider
webhooks and automatic reconciliation, then PayDunya, DexPay and NabooPay.

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
cp .env.example .env        # set POSTGRES_PASSWORD
docker compose up --build
curl localhost:8080/actuator/health
```

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

## Licence

Server, core and providers: [AGPL-3.0](LICENSE). Client libraries: Apache-2.0.
A commercial licence is available for companies that cannot use AGPL.

## Project layout

| Module | Contents |
| --- | --- |
| `yoon-core` | Domain: money, state machines, ledger model, provider interface. Pure Java, no dependencies. |
| `yoon-testkit` | `FakeProvider` and test helpers. |
| `yoon-server` | The Spring Boot gateway: database, and soon the API, webhooks and scheduler. |

Contributors and AI agents: read [CLAUDE.md](CLAUDE.md).
