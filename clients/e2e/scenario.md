# The end-to-end scenario, shared by every client

One scenario proves every client against a real Yoon server. Each client implements it as a
script in `clients/<lang>/e2e/…`; CI's `clients-e2e` job runs them all against the same
server. The scenario exercises the money-safety behaviours the clients must not hide:
idempotent retries, stable error codes, honest CSV.

Start a server in demo mode (no provider account, no real money), from the repository root:

```sh
cat > .env <<'ENV'
POSTGRES_PASSWORD=local-test
YOON_PUBLIC_URL=http://localhost:8080
YOON_DEMO_ENABLED=true
YOON_APPS_E2E_PROVIDERS_DEMO_PRIORITY=1
ENV
docker compose up -d --build --wait
KEY=$(docker compose run --rm -T yoon apps create e2e | grep -o 'yk_[A-Za-z0-9_-]*')
docker compose restart yoon && docker compose up -d --wait
```

Then run each client's script with `YOON_URL=http://localhost:8080` and
`YOON_API_KEY=$KEY`. The steps, in order:

1. `createPayment` (demo provider) → `pending`; the customer's phone comes back **masked**
   (`+22177***67`).
2. The same call with the **same idempotency key** → the **same payment id** (a replay
   returns the original, it does not charge twice).
3. `getPayment` → found; list payments by `reference` → the same payment is found.
4. `refund` of the pending payment → an error with `problemCode = payment_not_refundable`.
5. A call with a **bad API key** → an error with `problemCode = unauthorized`.
6. `exportCsv('payments')` → the body starts with `id,created_at` (the real header, observed
   from the server; the OpenAPI contract describes CSV as RFC 4180 but does not pin columns —
   this file is where the header is pinned for every client).
7. `createPayout` → `processing`.

A script stops at the first failed step with a non-zero exit code.
