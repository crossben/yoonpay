# CLAUDE.md

Guidance for AI coding agents working on Yoon, a self-hosted, open-source payment
gateway for African payment providers (PayDunya, DexPay, NabooPay first).

## Working rules

- **Never `git commit` or `git push`.** The owner commits. Finish a piece of
  work, summarise the changes, suggest a commit message, and stop.
- **Update [README.md](README.md) with every change that affects users**:
  status, what works, configuration table, how to run.
- Tests before or with every behaviour. No "tests later".
- Never log secrets, full phone numbers or card-like data. Mask phones
  (`+22177***45`).
- English for code and docs.
- Docs make no claim the code does not back up (no invented numbers or users).

## Build and run

Requires JDK 25 and a running Docker daemon (integration tests use Testcontainers).

```sh
./mvnw verify                                        # all tests, incl. Postgres integration tests
cp .env.example .env && docker compose up --build    # Yoon + Postgres on :8080
```

## Modules

| Module | What it holds | May depend on |
| --- | --- | --- |
| `yoon-core` | Money, state machines, shadow-ledger model, provider SPI | the JDK only |
| `yoon-testkit` | `FakeProvider` — scriptable provider for tests | `yoon-core` |
| `yoon-server` | Spring Boot 4 app: HTTP API, persistence; webhooks and scheduler next | core; testkit in test scope |

Package root: `dev.yoonpay`. Architecture decisions: `docs/adr/` (never rewrite
an ADR; supersede it with a new one). Database migrations: `yoon-server/src/main/resources/db/migration` (Flyway, forward-only — never edit an applied migration, add a new one).

## Rules the code relies on — do not break them

### Architecture

- `yoon-core` depends on nothing but the JDK. Enforced by `CoreArchitectureTest` (ArchUnit).
- Money is `Money(long amount, Currency)` in minor units; never negative; never
  `double`/`float` anywhere, including JSON. XOF has no minor unit: 5 000 XOF = `5000`.
- Data access: Spring Data JDBC and `JdbcClient`. **No JPA.** Writes must be explicit.
- **No Lombok.** Use records.
- **No provider SDKs.** Provider modules call the provider over HTTP with
  `RestClient`, timeouts on every call.
- Configuration only from environment variables; nothing sensitive has a default.

### Money safety (`dev.yoonpay.core.lifecycle`, `dev.yoonpay.core.provider.CallOutcome`)

- A mutating provider call has **three** outcomes: `Accepted`, `Rejected`, `Unknown`.
  A timeout, 5xx or dropped connection *after sending* is `Unknown` — never a
  failure. `Unknown` means: no failover, no retry; the status API resolves it.
  Only `Rejected` (definite answer, or provably never sent) allows failover.
- Payouts and refunds are never retried and never failed over once sent.
- State machines decide every status change: `APPLY`, `IGNORE` (duplicate or late
  news, still logged), or throw `IllegalTransitionException`. Never set a status
  without asking the machine.
- `SUCCEEDED`/`PAID`/`REFUNDED` ignore later failures. `FAILED`/`EXPIRED` **do**
  accept a later confirmed success — losing real money is worse than a surprised app.
- Webhooks are hints: verify the signature over the **raw body bytes**, then
  re-confirm through the provider's status API using the reference Yoon stored,
  never one read from the callback.
- Settle only when the confirmed amount equals the requested amount.

### Ledger (`ledger_postings`, `ledger_entries`)

- A shadow ledger: it mirrors money held in the application's own provider
  accounts; Yoon never holds funds.
- Every posting balances to zero per currency. Postgres enforces this at commit
  with deferred constraint triggers; the ledger is append-only (UPDATE/DELETE
  raise). Corrections are new postings. `LedgerDatabaseGuardTest` proves it.
- Account names come from `dev.yoonpay.core.ledger.Accounts` (per provider).

### API (`dev.yoonpay.server.api`, `api/openapi.yaml`)

- **Contract first.** `api/openapi.yaml` is hand-written and is the source of
  truth. Any new or changed route, parameter, field or status code is added to
  it in the same change. `ContractCoverageTest` fails if a `/v1` route is missing
  from the spec (or documented but not implemented); `ApiTest` validates every
  response against it. Swagger UI at `/docs` renders it — no code-generated spec.
- JSON is snake_case. Errors are problem+json via `ApiProblem` with a stable
  `code`; never return an error body by hand.
- Every mutating endpoint goes through `IdempotentCall` and requires
  `Idempotency-Key`.
- Every query is scoped to the authenticated `AppPrincipal` (a controller
  parameter). An application must never read another's data.
- Lists: newest first, keyset pagination on the time-ordered id
  (`starting_after`, `limit` ≤ 100), response `{data, has_more, next_cursor}`.
- Phone numbers are stored in E.164 (`Phones.normalize`) and always returned masked.

### Routing and failover (`Router`, `ProviderRegistry`, `*Service`)

- Payments fail over to the next provider only after `Rejected`; `Unknown`
  keeps the payment `PENDING` on that provider. Refunds go to the collecting
  provider. Payouts: one provider, one call, never failed over.
- Provider calls run outside DB transactions; each status change is a short
  transaction that locks the row, asks the state machine and records a
  `status_events` row.
- Every mutating call goes through `ProviderRegistry.call` (circuit breaker per
  application and provider).

### Idempotency (`IdempotencyStore`)

- Claim the key (`begin`) **before** any provider call; it commits on its own.
  Same key + same body → replay; different body → mismatch (409); still running
  → in progress (409 + Retry-After). A key stuck IN_PROGRESS is never re-executed.

## Testing

- Integration tests extend `dev.yoonpay.server.PostgresTest` (one shared Postgres
  container and running server, Flyway applied; `newApplication()` gives an
  isolated app id).
- API tests extend `dev.yoonpay.server.api.ApiTest`: real HTTP against the
  running server, apps `shop` and `other` with API keys, three fake providers
  (`TestProviders`: `fakeone`, `faketwo`, `fakenorefund`) reset before each test.
  Every response is checked against `api/openapi.yaml`.
- Provider behaviour is tested with `FakeProvider`: script `Behaviour`s (accept,
  reject, down, hang, timeout-after-accept), settle provider-side truth, emit
  genuine, duplicate, late or forged webhooks.
- State machine tests list the full from × to matrix; update the matrix
  deliberately when a rule changes.

## Licensing

`yoon-core`, `yoon-testkit`, `yoon-server` and providers: AGPL-3.0. Client
libraries (future `clients/`): Apache-2.0. External contributions require a CLA.
