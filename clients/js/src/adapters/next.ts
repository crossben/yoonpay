import { verifyWebhook } from "../verifyWebhook";
import { checkoutUrlOf } from "../billing";
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

/**
 * A 303 redirect to the hosted checkout of an invoice, or of a subscription's `latest_invoice`,
 * for App Router route handlers (and any Web `Response` runtime). Throws `NoCheckoutError`
 * (from `@yoonpay/yoon`) when there is nothing to pay — the invoice is paid or void, or its
 * checkout expired: call `yoon.renewSubscription(id, key)` for a fresh one.
 *
 * ```ts
 * // app/subscribe/route.ts
 * export async function POST() {
 *   const sub = await yoon.createSubscription({ customer, price, return_url }, `sub-${user.id}-${price}`);
 *   return checkoutRedirect(sub);
 * }
 * ```
 *
 * A server action cannot return a `Response`: there, call `redirect(checkoutUrlOf(sub))` with
 * `redirect` from `next/navigation`. This package does not depend on `next`.
 */
export function checkoutRedirect(invoiceOrSubscription: object): Response {
  const url = checkoutUrlOf(invoiceOrSubscription);
  return new Response(null, { status: 303, headers: { location: url } });
}

export { checkoutUrlOf, NoCheckoutError } from "../billing";

/**
 * A 303 to a fresh customer portal session (Yoon 0.6+), for a route handler such as
 * `app/account/billing/route.ts`. Call it for the signed-in user's own customer only. In a server
 * action use `redirect((await yoon.createPortalSession(id, options)).url)` instead.
 */
export async function portalRedirect(
  yoon: { createPortalSession(customerId: string, options?: { returnUrl?: string; allow?: object }): Promise<{ url: string }> },
  customerId: string,
  options: { returnUrl?: string; allow?: object } = {},
): Promise<Response> {
  const session = await yoon.createPortalSession(customerId, options);
  return new Response(null, { status: 303, headers: { location: session.url } });
}
