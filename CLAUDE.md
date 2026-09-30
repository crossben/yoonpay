# CLAUDE.md

Guidance for AI coding agents working on Yoon, a self-hosted, open-source payment
gateway for African payment providers (PayDunya, DexPay, NabooPay first).

This repository is the gateway. The website (yoonpay.benhattab.pro) is a separate repository,
checked out next to this one as `../website/`; its rules are in `../website/PLAN.md`.

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
| `yoon-providers/yoon-provider-support` | `ProviderHttp` (outcome classification), JSON, signatures, credentials | core, Jackson |
| `yoon-providers/yoon-provider-{paydunya,dexpay,naboopay}` | One adapter each | core, support, Jackson, JDK — no Spring (`ProvidersArchitectureTest`) |
| `yoon-providers/yoon-provider-demo` | Demo provider (`YOON_DEMO_ENABLED`), page in `server/demo` | same |
| `clients/php`, `clients/java`, `clients/js` | Apache-2.0 clients: `generated/` + thin hand-written layer | standalone builds |
| `examples/laravel-shop` | Laravel 13 app using `clients/php` | — |
| `yoon-server` | Spring Boot 4 app: HTTP API, persistence, provider callbacks, outbox, sweeps | core; testkit in test scope |

Package root: `dev.yoonpay`. Architecture decisions: `docs/adr/` (never rewrite
an ADR; supersede it with a new one). Database migrations: `yoon-server/src/main/resources/db/migration` (Flyway, forward-only — never edit an applied migration, add a new one).

## Rules the code relies on — do not break them

### Architecture

- `yoon-core` depends on nothing but the JDK. Enforced by `CoreArchitectureTest` (ArchUnit).
- Money is `Money(long amount, Currency)` in minor units; never negative; never
  `double`/`float` anywhere, including JSON. XOF has no minor unit: 5 000 XOF = `5000`.
- Data access: Spring Data JDBC and `JdbcClient`. **No JPA.** Writes must be explicit.
- **No Lombok.** Use records.
- **No provider SDKs.** Provider modules call the provider through `ProviderHttp`
  (JDK `HttpClient`), which classifies `NotSent` / `Lost` / `Answered` and never
  retries.
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
- Provider calls run outside DB transactions.
- **Every status change goes through `PaymentTransitions` / `RefundTransitions` /
  `PayoutTransitions`** — never update a status column directly. They lock the
  row, ask the state machine, record `status_events`, apply, post to the ledger
  and write the outbound event, in one transaction. Ledger postings and outbound
  events are written nowhere else.
- Every mutating call goes through `ProviderRegistry.call` (circuit breaker per
  application and provider).

### Webhooks, settlement, reconciliation (`webhook`, `settlement`, `outbox`)

- Inbound callbacks are stored raw and answered 200; `InboundWebhookProcessor`
  verifies the signature, looks the record up **within the application in the
  URL**, and calls `Settlement`. Never act on a callback's claimed status.
- `Settlement` is the one settle path for webhooks and sweeps: it asks the
  provider's status API with the stored reference (recovering it through
  `lookup` if the create answer was lost), refuses mismatched amounts, and
  applies through the transitions classes.
- `Reconciler` sweeps run under ShedLock; `Scheduler` is off in tests
  (`yoon.scheduling.enabled=false`) — tests call the worker/sweep methods and
  age rows with SQL instead of sleeping.
- Queue workers claim rows with a lease (`FOR UPDATE SKIP LOCKED`).
- Outbound events: `Outbox.emit` only inside a transitions transaction
  (`Propagation.MANDATORY`). Signature: `WebhookSignature`.
- Anything a human must look at goes through `Alerts.raise` (log + counter).

### Idempotency (`IdempotencyStore`)

- Claim the key (`begin`) **before** any provider call; it commits on its own.
  Same key + same body → replay; different body → mismatch (409); still running
  → in progress (409 + Retry-After). A key stuck IN_PROGRESS is never re-executed.

### Provider adapters (`yoon-providers/`)

- Map every mutating call to `Accepted` / `Rejected` / `Unknown`: `NotSent` →
  `Rejected("PROVIDER_UNAVAILABLE")`; `Lost` or 5xx → `Unknown` (with the
  reference if known); a 2xx you cannot read → `Unknown`; a definite refusal →
  `Rejected`. A step that moves no money (e.g. PayDunya `get-invoice`) may be
  `Rejected` on any failure.
- Status mapping lives in one `*Status` class; unknown raw values map to `null`
  (never guessed). A parameterised table test lists every raw value, and
  `docs/providers/<id>.md` shows the same table — keep them in sync.
- Declare only capabilities the provider really has; never fake refunds.
- `verify` checks the signature over the raw bytes and extracts the reference;
  it never decides a status.
- WireMock tests cover: happy path with request assertions, refusal, 5xx,
  timeout, refused connection, every callback variant.
- Adding a provider = one module + factory bean in `ProvidersConfiguration` +
  docs page + status table test + WireMock tests. If core must change, the SPI
  is wrong: fix it and write an ADR.

### Clients (`clients/`)

- `clients/*/generated` is produced by `./clients/generate.sh` (Docker) — **never edit it**.
  After changing `api/openapi.yaml`, run the script and commit the result; CI's
  contract-drift job fails otherwise. Fixes to generated output belong in the script.
- The hand-written layer stays thin: helpers take an explicit idempotency key; errors
  become `YoonException` with the problem `code`.
- Signature verification in every client must pass `api/test-vectors/webhook-signature.json`.
- `JavaClientTest` (server) runs the Java client against the real server; `clients/php` tests
  run on lowest and highest dependencies (Guzzle 7 and 8); `clients/js` tests run on Node 20 and
  current, with vitest, and its e2e script (`clients/js/e2e/run.mjs`) runs the shared scenario
  (`clients/e2e/scenario.md`) against a demo server in the `clients-e2e` CI job.
- The IDE may compile into `target/`: if Maven reports "Unresolved compilation problems",
  run with `clean`.

### Operations (`deploy/`, `docs/runbook.md`)

- Metric names used by `deploy/prometheus/alerts.yml` and the Grafana dashboard are pinned by
  `MetricsTest`; rename a metric only together with both.
- A change that alters operations (new env var, new alert, new admin action) updates the
  README configuration table, `deploy/.env.example` and `docs/runbook.md`.
- Validate deploy files after editing: `promtool check config|rules`, `caddy validate`,
  `docker compose config`; workflows with `actionlint`.
- `CHANGELOG.md` gets an entry for every user-visible change; the release workflow takes the
  release notes from it.

## Testing

- Integration tests extend `dev.yoonpay.server.PostgresTest` (one shared Postgres
  container and running server, Flyway applied; `newApplication()` gives an
  isolated app id).
- API tests extend `dev.yoonpay.server.api.ApiTest`: real HTTP against the
  running server, apps `shop` and `other` with API keys, three fake providers
  (`TestProviders`: `fakeone`, `faketwo`, `fakenorefund`) reset before each test.
  Every response is checked against `api/openapi.yaml`. `RECEIVER` plays the
  `shop` app's webhook endpoint; `admin(…)` calls the operator API;
  `postRaw(…)` sends provider callbacks. The `live` app uses the real DexPay
  adapter against the `DEXPAY` WireMock server (`LiveProviderTest`).
- Provider behaviour is tested with `FakeProvider`: script `Behaviour`s (accept,
  reject, down, hang, timeout-after-accept), settle provider-side truth, emit
  genuine, duplicate, late or forged webhooks.
- State machine tests list the full from × to matrix; update the matrix
  deliberately when a rule changes.

## Licensing

`yoon-core`, `yoon-testkit`, `yoon-server` and providers: AGPL-3.0. Client
libraries (`clients/`) and `examples/`: Apache-2.0. External contributions require a CLA.
