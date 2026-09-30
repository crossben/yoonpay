# @yoonpay/yoon

JavaScript/TypeScript client for [Yoon](https://github.com/crossben/yoonpay), a self-hosted payment
gateway for African payment providers. Apache-2.0.

Requires Node ≥ 20 (also runs on Bun, Deno and edge runtimes): the client uses only the global
`fetch` and Web Crypto — no Node-specific modules, no other dependency. TypeScript types included.

```sh
npm install @yoonpay/yoon
```

## Plain usage

```ts
import { Yoon, YoonException } from "@yoonpay/yoon";

const yoon = new Yoon("https://pay.example.com", process.env.YOON_API_KEY);

try {
  const payment = await yoon.createPayment(
    {
      amount: 5000, // XOF has no minor unit: 5 000 FCFA
      currency: "XOF",
      country: "SN",
      method: "wave", // wave, orange_money, free_money, card
      customer: { phone: "+221771234567" },
      reference: "order_1042",
      return_url: "https://shop.example/orders/1042",
    },
    "order-1042", // tie it to your order: a retry can never charge twice
  );

  // Send the customer to the provider's checkout:
  response.setHeader("Location", payment.checkout_url!);
} catch (e) {
  if (e instanceof YoonException) {
    e.problemCode; // e.g. no_provider_for_method, refund_exceeds_payment
    e.isRetryable(); // true: retry with the same idempotency key
  }
}
```

Helpers: `createPayment`, `getPayment`, `refund`, `createPayout`, `exportCsv`. Every other
operation of the API is on the generated API objects — `yoon.payments()`, `.refunds()`,
`.payouts()`, `.events()`, `.ledger()`, `.meta()` — wrapped with `yoon.call(fn)` to turn errors
into `YoonException`. The client **never retries** on its own: retry with the same idempotency
key when `isRetryable()` is true. Property names match the API contract exactly
(`checkout_url`, `amount_refunded`), so what you read is what Yoon sent.

Timeouts: the whole exchange must finish within 30 s (configurable `timeoutMs`), the response
headers within 5 s (`connectTimeoutMs`). A timeout means the request may have reached Yoon: it
throws a retryable `YoonException` with `problemCode === null`.

The client refuses to run in a browser (the API key would be public) unless you pass
`dangerouslyAllowBrowser: true`. Call Yoon from your backend.

## Frameworks

Adapters are separate entry points; their frameworks stay optional peers the core never pulls in.

### Express

```ts
import express from "express";
import { yoonWebhook } from "@yoonpay/yoon/express";

const app = express();
app.post(
  "/yoon/webhook",
  express.raw({ type: "application/json" }), // keep the raw body — signatures need exact bytes
  yoonWebhook({ secret: process.env.YOON_WEBHOOK_SECRET! }),
  (req, res) => {
    const event = req.yoonEvent!;
    if (event.type === "payment.succeeded") markPaid(event.object.id as string);
    res.sendStatus(200);
  },
);
```

Without a body parser the middleware reads the raw stream itself. With `express.json()` the body
is parsed and re-encoded, which **breaks the signature** — that is by design, and a test proves
it: a body you cannot prove is the body Yoon signed must be refused.

### Fastify

```ts
import { yoonWebhook } from "@yoonpay/yoon/fastify";

app.register(yoonWebhook, {
  secret: process.env.YOON_WEBHOOK_SECRET!,
  onEvent: (event) => {
    if (event.type === "payment.succeeded") markPaid(event.object.id as string);
  },
});
```

The plugin registers `/yoon/webhook` (configurable `path`) in an encapsulated scope that keeps
the raw body; the rest of your app is untouched.

### Next.js (App Router)

```ts
// app/yoon/webhook/route.ts
import { yoonWebhookRoute } from "@yoonpay/yoon/next";

export const POST = yoonWebhookRoute({
  secret: process.env.YOON_WEBHOOK_SECRET!,
  onEvent: (event) => {
    if (event.type === "payment.succeeded") markPaid(event.object.id as string);
  },
});
```

Route handlers receive the body raw, so this works out of the box. The same handler function
runs anywhere Web `Request`/`Response` exist — Hono, Bun, Deno, edge — as `verifyWebhook(request,
options)` from the main entry point.

### NestJS

```ts
// app.module.ts
import { YoonModule, YoonWebhookMiddleware } from "@yoonpay/yoon/nest";

@Module({
  imports: [YoonModule.forRoot({ url: "https://pay.example.com", apiKey: process.env.YOON_API_KEY!, webhookSecret: process.env.YOON_WEBHOOK_SECRET! })],
})
export class AppModule implements NestModule {
  configure(consumer: MiddlewareConsumer) {
    consumer.apply(YoonWebhookMiddleware).forRoutes("yoon/webhook");
  }
}
```

Inject the client with `@Inject(YOON_CLIENT)`. Nest parses JSON bodies by default, which breaks
signature verification — keep the raw body for Yoon's route only, in `main.ts`:

```ts
app.use(
  express.json({ type: (req) => !req.url!.startsWith("/yoon/webhook") }),
  express.raw({ type: (req) => req.url!.startsWith("/yoon/webhook") }),
);
```

## Webhooks

Every adapter behaves the same:

- a delivery whose signature or timestamp is wrong is refused with **401**;
- a body that is not a Yoon event is refused with **400**;
- an **already-handled event id** is answered **200 without reaching your handler**;
- an id is remembered **only after your handler answered 2xx** — a failed handling is
  delivered again.

Already-handled ids are remembered in a pluggable store (`store` option), by default an
in-process map kept for 3 days. Run more than one instance? Plug a shared cache — anything with
`has(id)` and `add(id)`:

```ts
yoonWebhook({ secret, store: redisStore });
```

Outside a framework, verify and parse yourself:

```ts
import { verifySignature, YoonWebhookEvent } from "@yoonpay/yoon";

const authentic = await verifySignature(secret, req.headers["yoon-signature"], rawBody);
const event = YoonWebhookEvent.fromJson(rawBody); // id, type, object
```

## Errors

`YoonException` carries:

- `httpStatus` — `null` when Yoon could not be reached;
- `problemCode` — Yoon's stable `code` (e.g. `no_provider_for_method`), `null` when Yoon was
  unreachable, `unreadable_response` when Yoon answered something this client could not read;
- `problem` — the full problem document, when there was one;
- `isRetryable()` — true when retrying with the same idempotency key is the right move:
  unreachable, busy (`idempotency_in_progress`) or failing (5xx). An unreadable answer is
  **not** retryable.

The API key never appears in error messages, stacks or `toString()`.

## Layout

- `generated/` — generated from `api/openapi.yaml` by `clients/generate.sh`. Never edit by
  hand; CI fails if it drifts from the contract.
- `src/` — the hand-written layer: `Yoon`, `YoonException`, webhook verification, `verifyWebhook`
  and the framework adapters.
- `e2e/run.mjs` — the shared scenario (`clients/e2e/scenario.md`) against a real server in demo
  mode. `clients/e2e/scenario.md` describes it once; every client implements the same steps.

```sh
npm install
npm test        # vitest: signature vector, client behaviour, every framework adapter
npm run build   # tsup: ESM + CJS + types into dist/
```
