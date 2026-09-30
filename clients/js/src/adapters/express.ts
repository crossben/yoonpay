import type { NextFunction, Request, RequestHandler, Response } from "express";
import { YOON_SIGNATURE_HEADER, YoonWebhookEvent, verifySignature } from "../Webhook";
import { MemoryEventStore, type YoonWebhookOptions, type YoonWebhookStore } from "../WebhookReceiver";

declare global {
  // eslint-disable-next-line @typescript-eslint/no-namespace
  namespace Express {
    interface Request {
      /** The verified event — present after the middleware passed the request on. */
      yoonEvent?: YoonWebhookEvent;
    }
  }
}

/**
 * The raw request body, without assuming a body parser:
 * - a Buffer or string (e.g. `express.raw({ type: "application/json" })` — recommended) is
 *   used as is;
 * - a parsed object (e.g. `express.json()`) is re-encoded, which **breaks the signature** —
 *   JSON round-trips are not byte-stable, and that is a feature: a body you cannot prove is
 *   the body Yoon signed must be refused;
 * - with no parser at all, the untouched stream is read (also raw).
 */
async function rawBody(req: Request): Promise<Uint8Array> {
  const body = (req as Request & { body?: unknown }).body;
  if (Buffer.isBuffer(body) || body instanceof Uint8Array) return new Uint8Array(body);
  if (typeof body === "string") return new TextEncoder().encode(body);
  if (body !== undefined && body !== null && typeof body === "object") {
    return new TextEncoder().encode(JSON.stringify(body));
  }
  const chunks: Buffer[] = [];
  for await (const chunk of req) {
    chunks.push(Buffer.from(chunk as Buffer));
  }
  return new Uint8Array(Buffer.concat(chunks));
}

/**
 * Express middleware for the endpoint Yoon posts events to:
 *
 * ```ts
 * app.post(
 *   "/yoon/webhook",
 *   express.raw({ type: "application/json" }), // keep the raw body
 *   yoonWebhook({ secret: process.env.YOON_WEBHOOK_SECRET! }),
 *   (req, res) => {
 *     const event = req.yoonEvent!;
 *     if (event.type === "payment.succeeded") markPaid(event.object.id);
 *     res.sendStatus(200);
 *   },
 * );
 * ```
 *
 * Rejects deliveries whose signature or timestamp is wrong (401), answers an
 * already-handled event id with 200 without calling the next handler, and puts the parsed
 * {@link YoonWebhookEvent} on `req.yoonEvent`. The id is remembered only after the response
 * turned out to be 2xx, so a failed handling is delivered again. Uses Express types only —
 * the framework stays a peer you already have.
 */
export function yoonWebhook(options: YoonWebhookOptions): RequestHandler {
  const store: YoonWebhookStore = options.store ?? new MemoryEventStore();
  return (req: Request, res: Response, next: NextFunction) => {
    void (async () => {
      const now = options.now?.() ?? Math.floor(Date.now() / 1000);
      const raw = await rawBody(req);
      const authentic = await verifySignature(
        options.secret,
        req.get(YOON_SIGNATURE_HEADER),
        raw,
        now,
        options.toleranceSeconds,
      );
      if (!authentic) {
        res.status(401).json({ error: "invalid signature" });
        return;
      }

      let event: YoonWebhookEvent;
      try {
        event = YoonWebhookEvent.fromJson(raw);
      } catch {
        res.status(400).json({ error: "not a Yoon event" });
        return;
      }

      if (await store.has(event.id)) {
        res.status(200).json({ duplicate: true });
        return;
      }

      req.yoonEvent = event;
      res.on("finish", () => {
        if (res.statusCode >= 200 && res.statusCode < 300) void store.add(event.id);
      });
      next();
    })().catch(() => {
      // Reading the body failed before anything was sent: refuse rather than hang.
      if (!res.headersSent) res.status(400).json({ error: "invalid signature" });
    });
  };
}
