import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { Yoon } from "@yoonpay/yoon";
import { z } from "zod";
import type { Results } from "./result";

const READ_ONLY = { readOnlyHint: true, openWorldHint: false } as const;

/** Lists return at most this many items per call; `next_cursor` continues. */
export const MAX_PAGE = 50;

const page = {
  limit: z.number().int().min(1).max(MAX_PAGE).optional().describe(`Items per page, at most ${MAX_PAGE} (default 20).`),
  starting_after: z.string().optional().describe("The next_cursor of the previous page."),
};
const id = (what: string) => ({ id: z.string().min(1).describe(`The ${what} id.`) });
const date = (what: string) => z.string().optional().describe(`${what}, ISO 8601 (e.g. 2026-09-01T00:00:00Z).`);

const paging = (args: { limit?: number; starting_after?: string }) => ({
  limit: args.limit ?? 20,
  starting_after: args.starting_after,
});

/* eslint-disable @typescript-eslint/no-explicit-any -- enum filters are open sets, passed as given. */
export function registerReadTools(server: McpServer, yoon: Yoon, results: Results): void {
  const tool = <S extends z.ZodRawShape>(
    name: string,
    description: string,
    inputSchema: S,
    fn: (args: z.objectOutputType<S, z.ZodTypeAny>) => Promise<unknown>,
  ) =>
    server.registerTool(name, { description, inputSchema, annotations: READ_ONLY }, ((args: any) =>
      results.run(() => fn(args))) as any);
  const call = <T>(fn: () => Promise<T>) => yoon.call(fn);

  // Payments
  tool("get_payment", "One payment: status, amount, method, provider, failure.", id("payment (pay_…)"), (a) =>
    call(() => yoon.payments().getPayment({ id: a.id })),
  );
  tool(
    "list_payments",
    "Payments, newest first. Filters: status (created, pending, succeeded, failed, expired), method, provider, your reference, dates.",
    {
      status: z.string().optional(),
      method: z.string().optional(),
      provider: z.string().optional(),
      reference: z.string().optional().describe("Your order reference."),
      created_from: date("Created at or after"),
      created_to: date("Created before"),
      ...page,
    },
    (a) =>
      call(() =>
        yoon.payments().listPayments({
          status: a.status as any,
          method: a.method,
          provider: a.provider,
          reference: a.reference,
          created_from: a.created_from,
          created_to: a.created_to,
          ...paging(a),
        }),
      ),
  );
  tool(
    "explain_payment",
    "Why a payment is in its status: the payment, its full status history in order, and a short summary.",
    id("payment (pay_…)"),
    async (a) => {
      const [payment, history] = await Promise.all([
        call(() => yoon.payments().getPayment({ id: a.id })),
        call(() => yoon.payments().listPaymentEvents({ id: a.id })),
      ]);
      return { summary: summarize(payment, history), payment, history };
    },
  );

  // Refunds and payouts
  tool("get_refund", "One refund.", id("refund (re_…)"), (a) => call(() => yoon.refunds().getRefund({ id: a.id })));
  tool(
    "list_refunds",
    "Refunds, newest first; or the refunds of one payment.",
    { payment_id: z.string().optional(), status: z.string().optional(), ...page },
    (a) =>
      a.payment_id
        ? call(() => yoon.refunds().listPaymentRefunds({ id: a.payment_id! }))
        : call(() => yoon.refunds().listRefunds({ status: a.status as any, ...paging(a) })),
  );
  tool("get_payout", "One payout (money sent out).", id("payout (po_…)"), (a) =>
    call(() => yoon.payouts().getPayout({ id: a.id })),
  );
  tool(
    "list_payouts",
    "Payouts, newest first. needs_review=true: payouts whose outcome is unknown and need a human.",
    { status: z.string().optional(), reference: z.string().optional(), needs_review: z.boolean().optional(), ...page },
    (a) =>
      call(() =>
        yoon.payouts().listPayouts({ status: a.status as any, reference: a.reference, needs_review: a.needs_review, ...paging(a) }),
      ),
  );

  // Customers, subscriptions, invoices
  tool("get_customer", "One customer.", id("customer (cus_…)"), (a) =>
    call(() => yoon.customers().getCustomer({ id: a.id })),
  );
  tool(
    "list_customers",
    "Customers, newest first; or the one with your external_id.",
    { external_id: z.string().optional(), ...page },
    (a) => call(() => yoon.customers().listCustomers({ external_id: a.external_id, ...paging(a) })),
  );
  tool(
    "get_subscription",
    "One subscription with its status history.",
    id("subscription (sub_…)"),
    async (a) => {
      const [subscription, history] = await Promise.all([
        call(() => yoon.subscriptions().getSubscription({ id: a.id })),
        call(() => yoon.subscriptions().listSubscriptionEvents({ id: a.id })),
      ]);
      return { subscription, history };
    },
  );
  tool(
    "list_subscriptions",
    "Subscriptions. Filters: customer, status (trialing, active, past_due, expired, canceled…).",
    { customer: z.string().optional(), status: z.string().optional(), ...page },
    (a) =>
      call(() => yoon.subscriptions().listSubscriptions({ customer: a.customer, status: a.status as any, ...paging(a) })),
  );
  tool("get_invoice", "One invoice with its lines, payment and receipt link.", id("invoice (in_…)"), (a) =>
    call(() => yoon.subscriptions().getInvoice({ id: a.id })),
  );
  tool(
    "list_invoices",
    "Invoices. Filters: customer, subscription, status (open, paid, void…).",
    { customer: z.string().optional(), subscription: z.string().optional(), status: z.string().optional(), ...page },
    (a) =>
      call(() =>
        yoon.subscriptions().listInvoices({
          customer: a.customer,
          subscription: a.subscription,
          status: a.status as any,
          ...paging(a),
        }),
      ),
  );

  // Payment links
  tool("get_payment_link", "One payment link with its payment count and amount collected.", id("payment link (plink_…)"), (a) =>
    call(() => yoon.paymentLinks().getPaymentLink({ id: a.id })),
  );
  tool(
    "list_payment_links",
    "Payment links; active=true for the ones that can still be paid.",
    { active: z.boolean().optional(), ...page },
    (a) => call(() => yoon.paymentLinks().listPaymentLinks({ active: a.active, ...paging(a) })),
  );

  // Money: balances and ledger
  tool("get_balances", "Balances per provider and currency, from Yoon's double-entry ledger.", {}, () =>
    call(() => yoon.ledger().getBalances()),
  );
  tool(
    "list_ledger_entries",
    "Ledger entries (double entry). Filters: provider, dates.",
    {
      provider: z.string().optional(),
      from: date("From"),
      to: date("Before"),
      limit: page.limit,
      starting_after: z.number().int().optional().describe("The next_cursor of the previous page."),
    },
    (a) =>
      call(() =>
        yoon.ledger().listLedgerEntries({
          provider: a.provider,
          from: a.from as any,
          to: a.to as any,
          limit: a.limit ?? 20,
          starting_after: a.starting_after,
        }),
      ),
  );

  // Events (webhooks to your application)
  tool("get_event", "One event and its webhook delivery status.", id("event (evt_…)"), (a) =>
    call(() => yoon.events().getEvent({ id: a.id })),
  );
  tool(
    "list_events",
    "Events sent to your application's webhook. Filters: type (e.g. payment.succeeded), delivery_status (e.g. failed), resource_id.",
    { type: z.string().optional(), delivery_status: z.string().optional(), resource_id: z.string().optional(), ...page },
    (a) =>
      call(() =>
        yoon.events().listEvents({
          type: a.type as any,
          delivery_status: a.delivery_status as any,
          resource_id: a.resource_id,
          ...paging(a),
        }),
      ),
  );
}

interface StatusChange {
  from_status?: string | null;
  to_status?: string | null;
  cause?: string | null;
  created_at?: Date | string | null;
}

/** Built from Yoon's own fields only (status, codes, causes) — never from customer text. */
export function summarize(payment: unknown, history: unknown): string {
  const p = payment as {
    id: string;
    status: string;
    amount: number;
    currency: string;
    method?: string | null;
    provider?: string | null;
    failure?: { code?: string | null } | null;
  };
  const changes = (history as StatusChange[]) ?? [];
  const parts = [`${p.id} is ${p.status}: ${p.amount} ${p.currency}`];
  if (p.method || p.provider) parts.push(`by ${p.method ?? "?"} via ${p.provider ?? "no provider yet"}`);
  if (p.failure?.code) parts.push(`failure code ${p.failure.code}`);
  const last = changes[changes.length - 1];
  if (last) {
    const at = last.created_at instanceof Date ? last.created_at.toISOString() : last.created_at;
    parts.push(`last change ${last.from_status ?? "∅"} → ${last.to_status} (cause ${last.cause ?? "?"}) at ${at}`);
  }
  parts.push(`${changes.length} status change${changes.length === 1 ? "" : "s"}`);
  return `${parts.join("; ")}.`;
}
