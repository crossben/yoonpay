import { YOON_SIGNATURE_HEADER } from "./Webhook";
import { receiveWebhook, type YoonWebhookHandler, type YoonWebhookOptions } from "./WebhookReceiver";

/**
 * Framework-free webhook handling for anything that speaks Web `Request`/`Response`:
 * Hono, Bun, Deno, workers, edge runtimes — and Next.js App Router route handlers
 * (see `@yoonpay/yoon/next` for the thin wrapper).
 *
 * ```ts
 * export async function POST(request: Request) {
 *   return verifyWebhook(request, { secret: process.env.YOON_WEBHOOK_SECRET!, onEvent });
 * }
 * ```
 *
 * Behaviour: invalid signature → 401; body that is not a Yoon event → 400; an
 * already-handled event id → 200 without reaching `onEvent`; `onEvent` remembered
 * only when it resolves — a failing handling is delivered again (500).
 */
export async function verifyWebhook(
  request: Request,
  options: YoonWebhookOptions & { onEvent: YoonWebhookHandler },
): Promise<Response> {
  const rawBody = new Uint8Array(await request.arrayBuffer());
  const header = request.headers.get(YOON_SIGNATURE_HEADER);
  let result;
  try {
    result = await receiveWebhook(rawBody, header, options, options.onEvent);
  } catch {
    return Response.json({ error: "handler failed" }, { status: 500 });
  }
  return Response.json(result.body, { status: result.status });
}
