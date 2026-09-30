import { createHmac } from "node:crypto";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

export const SECRET = "whsec_test_secret_0123456789abcdef";

/** The shared vector from the gateway repository (§4.1 of docs/plans/more-clients.md). */
export const signatureVector = JSON.parse(
  readFileSync(fileURLToPath(new URL("../../../api/test-vectors/webhook-signature.json", import.meta.url)), "utf8"),
) as { secret: string; timestamp: number; body: string; header: string };

/** Signs like Yoon does (tests may use node:crypto; the core stays on Web Crypto). */
export function sign(secret: string, body: string, timestamp: number): string {
  const v1 = createHmac("sha256", secret).update(`${timestamp}.${body}`).digest("hex");
  return `t=${timestamp},v1=${v1}`;
}

export const T = 1_790_000_000; // fixed clock for signatures

export const EVENT_BODY = JSON.stringify({
  id: "evt_0199a3f0c2e47a1b9c3d5e6f7a8b9c0d",
  object: "event",
  type: "payment.succeeded",
  created_at: "2026-09-30T10:00:00Z",
  data: {
    object: {
      id: "pay_0199a3f0c2e47a1b9c3d5e6f7a8b9c0d",
      object: "payment",
      status: "succeeded",
      amount: 5000,
      amount_refunded: 0,
      currency: "XOF",
      country: "SN",
      method: "wave",
      customer: { phone: "+22177***67" },
      created_at: "2026-09-30T10:00:00Z",
      updated_at: "2026-09-30T10:00:00Z",
    },
  },
});

export const PAYMENT_PENDING = {
  id: "pay_0199a3f0c2e47a1b9c3d5e6f7a8b9c0d",
  object: "payment",
  status: "pending",
  amount: 5000,
  amount_refunded: 0,
  currency: "XOF",
  country: "SN",
  method: "wave",
  reference: "order_1042",
  customer: { phone: "+22177***67" },
  provider: "demo",
  checkout_url: "http://127.0.0.1:8080/demo/checkout/x",
  created_at: "2026-09-30T10:00:00Z",
  updated_at: "2026-09-30T10:00:00Z",
};

export function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json", ...headers },
  });
}
