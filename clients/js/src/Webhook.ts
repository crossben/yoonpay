/**
 * Verifies `Yoon-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, "<t>.<raw body>")>`
 * using only Web Crypto (`crypto.subtle`), so it runs on Node, Bun, Deno and edge runtimes.
 * Always pass the raw request body exactly as received — not re-encoded JSON.
 */
export const YOON_SIGNATURE_HEADER = "Yoon-Signature";
export const SIGNATURE_TOLERANCE_SECONDS = 300;

const encoder = new TextEncoder();

function toBytes(rawBody: string | Uint8Array): Uint8Array<ArrayBuffer> {
  return typeof rawBody === "string" ? encoder.encode(rawBody) : new Uint8Array(rawBody);
}

function concat(a: Uint8Array<ArrayBuffer>, b: Uint8Array<ArrayBuffer>): Uint8Array<ArrayBuffer> {
  const out = new Uint8Array(a.length + b.length);
  out.set(a, 0);
  out.set(b, a.length);
  return out;
}

function toHex(buffer: ArrayBuffer): string {
  return [...new Uint8Array(buffer)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Compares two equal-length byte strings in constant time; unequal lengths are never equal. */
function timingSafeEqual(a: Uint8Array<ArrayBuffer>, b: Uint8Array<ArrayBuffer>): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i]! ^ b[i]!;
  return diff === 0;
}

async function hmacHex(secret: string, data: Uint8Array<ArrayBuffer>): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  return toHex(await crypto.subtle.sign("HMAC", key, data));
}

export async function verifySignature(
  secret: string,
  header: string | null | undefined,
  rawBody: string | Uint8Array,
  now: number = Math.floor(Date.now() / 1000),
  toleranceSeconds: number = SIGNATURE_TOLERANCE_SECONDS,
): Promise<boolean> {
  if (typeof secret !== "string" || secret.length === 0 || !header) return false;

  let timestamp: number | null = null;
  let signature: string | null = null;
  for (const part of header.split(",")) {
    const eq = part.indexOf("=");
    if (eq === -1) continue;
    const key = part.slice(0, eq).trim();
    const value = part.slice(eq + 1).trim();
    if (key === "t" && /^\d+$/.test(value)) {
      timestamp = Number(value);
    } else if (key === "v1") {
      signature = value;
    }
  }
  if (timestamp === null || signature === null) return false;
  if (Math.abs(now - timestamp) > toleranceSeconds) return false;

  const expected = encoder.encode(await hmacHex(secret, concat(encoder.encode(`${timestamp}.`), toBytes(rawBody))));
  const given = encoder.encode(signature.toLowerCase());
  return timingSafeEqual(expected, given);
}

/** Builds a header — for tests of your own webhook handler. */
export async function sign(
  secret: string,
  rawBody: string | Uint8Array,
  timestamp: number = Math.floor(Date.now() / 1000),
): Promise<string> {
  return `t=${timestamp},v1=${await hmacHex(secret, concat(encoder.encode(`${timestamp}.`), toBytes(rawBody)))}`;
}

/**
 * A verified event from Yoon. Delivery is at-least-once and unordered: deduplicate on `id`
 * and act on the state in `object` (or re-fetch it), not on arrival order.
 */
export class YoonWebhookEvent {
  readonly id: string;
  readonly type: string;
  /** The payment, refund or payout as the API returns it. */
  readonly object: Record<string, unknown>;
  /** The full event payload. */
  readonly payload: Record<string, unknown>;

  private constructor(payload: Record<string, unknown>) {
    this.payload = payload;
    this.id = payload.id as string;
    this.type = payload.type as string;
    this.object = (payload.data as Record<string, unknown>).object as Record<string, unknown>;
  }

  static fromJson(rawBody: string | Uint8Array): YoonWebhookEvent {
    let payload: unknown;
    try {
      payload = JSON.parse(new TextDecoder().decode(toBytes(rawBody)));
    } catch {
      throw new Error("Not a Yoon event");
    }
    const record = payload as Record<string, unknown>;
    const data = record?.data as Record<string, unknown> | undefined;
    if (
      record === null ||
      typeof record !== "object" ||
      typeof record.id !== "string" ||
      typeof record.type !== "string" ||
      data === null ||
      typeof data !== "object" ||
      data.object === null ||
      typeof data.object !== "object"
    ) {
      throw new Error("Not a Yoon event");
    }
    return new YoonWebhookEvent(record);
  }
}
