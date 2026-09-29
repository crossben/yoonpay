# Yoon

> *Yoon* (Wolof): the way, the road. Yoon picks the way a payment travels.

**A self-hosted, open-source payment gateway for Africa's payment providers.**
One API in front of PayDunya, DexPay and NabooPay (more later): routing,
verified webhooks, idempotency, a ledger and automatic reconciliation — written
once, in Java, instead of in every project.

> **Status: early development (milestone M0).** Not usable yet. See [plan.md](plan.md).

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

Build and test (needs Docker for Testcontainers):

```sh
./mvnw verify
```

## Configuration

| Variable | Required | Meaning |
|---|---|---|
| `YOON_DB_URL` | yes | JDBC URL, e.g. `jdbc:postgresql://localhost:5432/yoon` |
| `YOON_DB_USER` | yes | Database user |
| `YOON_DB_PASSWORD` | yes | Database password |

## Licence

Server, core and providers: [AGPL-3.0](LICENSE). Client libraries: Apache-2.0.
A commercial licence is available for companies that cannot use AGPL.
Design decisions: [docs/adr/](docs/adr/).
