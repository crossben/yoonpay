import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { Yoon } from "@yoonpay/yoon";
import { z } from "zod";
import type { Results } from "./result";

/**
 * The only writes an assistant gets (ADR-0032, choice 1A), listed only with
 * YOON_MCP_WRITES=true. None sends money out: no refund, payout, cancel, comp or coupon.
 * They create, never destroy, and a retried call with the same key does one thing.
 */
const WRITE = { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false } as const;

const idempotencyKey = z
  .string()
  .min(8)
  .max(255)
  .describe(
    "A unique key for this action (e.g. a UUID). Reuse it to retry the same action; a new key makes a new one.",
  );

/* eslint-disable @typescript-eslint/no-explicit-any */
export function registerWriteTools(server: McpServer, yoon: Yoon, results: Results): void {
  const tool = <S extends z.ZodRawShape>(
    name: string,
    description: string,
    inputSchema: S,
    fn: (args: z.objectOutputType<S, z.ZodTypeAny>) => Promise<unknown>,
  ) =>
    server.registerTool(name, { description, inputSchema, annotations: WRITE }, ((args: any) =>
      results.run(() => fn(args))) as any);

  tool(
    "create_payment_link",
    "Create a payment link (money comes in): a URL and QR code the merchant shares; the customer pays it " +
      "on Yoon's hosted checkout. Give `amount`, or leave it out to let the customer type it (optionally " +
      "between min_amount and max_amount). Amounts in minor units (XOF: 5000 is 5 000 XOF). Confirm the " +
      "amount and description with the user first.",
    {
      idempotency_key: idempotencyKey,
      description: z.string().min(1).max(500).describe("Shown to the customer, e.g. 'Tote bag, size M'."),
      currency: z.string().length(3).describe("e.g. XOF"),
      country: z.string().length(2).describe("e.g. SN"),
      amount: z.number().int().positive().optional(),
      min_amount: z.number().int().positive().optional(),
      max_amount: z.number().int().positive().optional(),
      max_payments: z.number().int().positive().optional().describe("1 for single use; leave out for reusable."),
      method: z.string().optional().describe("Limit to one method, e.g. wave."),
      expires_at: z.string().optional().describe("ISO 8601."),
      return_url: z.string().url().optional(),
    },
    (a) =>
      yoon.call(() =>
        yoon.paymentLinks().createPaymentLink({
          Idempotency_Key: a.idempotency_key,
          CreatePaymentLinkRequest: {
            description: a.description,
            currency: a.currency.toUpperCase(),
            country: a.country.toUpperCase(),
            amount: a.amount,
            min_amount: a.min_amount,
            max_amount: a.max_amount,
            max_payments: a.max_payments,
            method: a.method,
            expires_at: a.expires_at,
            return_url: a.return_url,
          },
        }),
      ),
  );

  tool(
    "create_portal_session",
    "Create a customer portal link for one customer: they can see their subscriptions and invoices and " +
      "pay. View-and-pay only — cancelling, changing plan or coupons are not offered from here. The link " +
      "is private to that customer and expires (1 hour by default): give it only to them.",
    {
      idempotency_key: idempotencyKey,
      customer_id: z.string().min(1).describe("The customer id (cus_…)."),
      return_url: z.string().url().optional().describe("Where the 'Back' button goes."),
    },
    (a) => yoon.createPortalSession(a.customer_id, { returnUrl: a.return_url }, a.idempotency_key),
  );

  tool(
    "redeliver_event",
    "Send one event to the application's own webhook again, with a fresh set of attempts (for example " +
      "after the application was down). Moves no money.",
    { idempotency_key: idempotencyKey, id: z.string().min(1).describe("The event id (evt_…).") },
    (a) => yoon.call(() => yoon.events().redeliverEvent({ id: a.id, Idempotency_Key: a.idempotency_key })),
  );
}
