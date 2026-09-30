import { verifyWebhook } from "../verifyWebhook";
import type { YoonWebhookHandler, YoonWebhookOptions } from "../WebhookReceiver";

export interface YoonNextWebhookOptions extends YoonWebhookOptions {
  onEvent: YoonWebhookHandler;
}

/**
 * Next.js App Router route handler for the endpoint Yoon posts events to. Next's route
 * handlers receive the request body raw, so signatures verify out of the box:
 *
 * ```ts
 * // app/yoon/webhook/route.ts
 * import { yoonWebhookRoute } from "@yoonpay/yoon/next";
 *
 * export const POST = yoonWebhookRoute({
 *   secret: process.env.YOON_WEBHOOK_SECRET!,
 *   onEvent: (event) => { if (event.type === "payment.succeeded") markPaid(event.object.id); },
 * });
 * ```
 *
 * (Route handlers run on the server — the browser guard does not affect them.)
 */
export function yoonWebhookRoute(options: YoonNextWebhookOptions): (request: Request) => Promise<Response> {
  return (request) => verifyWebhook(request, options);
}
