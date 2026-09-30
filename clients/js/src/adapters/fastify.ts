import type { FastifyPluginAsync } from "fastify";
import { YOON_SIGNATURE_HEADER } from "../Webhook";
import { MemoryEventStore, receiveWebhook, type YoonWebhookHandler, type YoonWebhookOptions } from "../WebhookReceiver";

export interface YoonFastifyWebhookOptions extends YoonWebhookOptions {
  /** Route path for Yoon's deliveries (default `/yoon/webhook`). */
  path?: string;
  onEvent: YoonWebhookHandler;
}

/**
 * Fastify plugin for the endpoint Yoon posts events to. Registers an encapsulated route
 * (default `/yoon/webhook`) whose scope keeps the raw body: invalid signature → 401; an
 * already-handled event id → 200 without calling `onEvent`; the id is remembered only
 * after `onEvent` resolved — a failing handling is delivered again (500, Fastify's own
 * error reply).
 *
 * ```ts
 * import { yoonWebhook } from "@yoonpay/yoon/fastify";
 *
 * app.register(yoonWebhook, {
 *   secret: process.env.YOON_WEBHOOK_SECRET!,
 *   onEvent: (event) => { if (event.type === "payment.succeeded") markPaid(event.object.id); },
 * });
 * ```
 */
export const yoonWebhook: FastifyPluginAsync<YoonFastifyWebhookOptions> = async (app, opts) => {
  // One store per registration — creating it per request would forget every event.
  const options: YoonFastifyWebhookOptions = { ...opts, store: opts.store ?? new MemoryEventStore() };
  // Encapsulated: only Yoon's route sees the raw-body parser; the rest of the app is untouched.
  app.addContentTypeParser(
    "application/json",
    { parseAs: "buffer" },
    (_request, body, done) => {
      done(null, body);
    },
  );

  app.post(options.path ?? "/yoon/webhook", async (request, reply) => {
    const result = await receiveWebhook(
      request.body as Buffer,
      (request.headers[YOON_SIGNATURE_HEADER.toLowerCase()] as string | undefined) ?? null,
      options,
      options.onEvent,
    );
    return reply.code(result.status).send(result.body);
  });
};
