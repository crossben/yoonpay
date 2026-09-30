import { YoonWebhookEvent, YOON_SIGNATURE_HEADER, verifySignature } from "./Webhook";

/**
 * Where already-handled event ids are remembered. The default is an in-process
 * {@link MemoryEventStore}; plug your cache (Redis, the framework's store) to survive
 * restarts and multiple instances.
 */
export interface YoonWebhookStore {
  has(id: string): boolean | PromiseLike<boolean>;
  add(id: string): void | PromiseLike<void>;
}

/**
 * In-memory store. Entries expire after `ttlSeconds` (default 3 days — longer than
 * Yoon's 12 retries with exponential backoff). Use a shared cache when you run more
 * than one instance: this one only knows its own process.
 */
export class MemoryEventStore implements YoonWebhookStore {
  private readonly entries = new Map<string, number>();
  private readonly ttlMs: number;

  constructor(ttlSeconds = 259_200) {
    this.ttlMs = ttlSeconds * 1000;
  }

  has(id: string): boolean {
    const seen = this.entries.get(id);
    if (seen === undefined) return false;
    if (Date.now() - seen > this.ttlMs) {
      this.entries.delete(id);
      return false;
    }
    return true;
  }

  add(id: string): void {
    this.entries.set(id, Date.now());
    if (this.entries.size > 10_000) {
      // Cheap self-defence against unbounded growth; the TTL sweep handles the rest.
      const oldest = [...this.entries.entries()].sort((a, b) => a[1] - b[1])[0];
      if (oldest) this.entries.delete(oldest[0]);
    }
  }
}

/**
 * Shared by every call that passes no store. A store created per call would forget each
 * event at once: route handlers (Next.js, edge) often build their options per request.
 */
const defaultStore = new MemoryEventStore();

export interface YoonWebhookOptions {
  /** The same value as `YOON_APPS_<APP>_WEBHOOK_SECRET` on the Yoon side (≥ 32 characters). */
  secret: string;
  store?: YoonWebhookStore;
  toleranceSeconds?: number;
  /** Override the clock (unix seconds) — for tests. */
  now?: () => number;
}

export type YoonWebhookHandler = (event: YoonWebhookEvent) => void | Promise<void>;

/**
 * The one place the webhook contract lives, so every framework adapter behaves the same:
 * a bad signature is refused (401), an unreadable body is refused (400), an
 * already-handled event is answered 200 without reaching the handler, and an id is
 * remembered only after the handler succeeded — a failed handling is delivered again.
 */
export async function receiveWebhook(
  rawBody: string | Uint8Array,
  signatureHeader: string | null | undefined,
  options: YoonWebhookOptions,
  handler: YoonWebhookHandler,
): Promise<{ status: number; body: Record<string, unknown>; event?: YoonWebhookEvent }> {
  const store = options.store ?? defaultStore;
  const now = options.now?.() ?? Math.floor(Date.now() / 1000);

  const authentic = await verifySignature(
    options.secret,
    signatureHeader,
    rawBody,
    now,
    options.toleranceSeconds,
  );
  if (!authentic) return { status: 401, body: { error: "invalid signature" } };

  let event: YoonWebhookEvent;
  try {
    event = YoonWebhookEvent.fromJson(rawBody);
  } catch {
    return { status: 400, body: { error: "not a Yoon event" } };
  }

  if (await store.has(event.id)) return { status: 200, body: { duplicate: true } };

  await handler(event);
  await store.add(event.id);
  // The event stays out of the reply body: Yoon does not need it echoed back, and it would
  // copy payment data into whatever logs the reply passes through.
  return { status: 200, body: { received: true }, event };
}

export { YoonWebhookEvent, YOON_SIGNATURE_HEADER, verifySignature };
