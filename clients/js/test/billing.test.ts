import { afterEach, describe, expect, it, vi } from "vitest";
import {
  ENTITLEMENT_EVENT_TYPES,
  INFORMATIONAL_BILLING_EVENT_TYPES,
  NoCheckoutError,
  changesEntitlement,
  checkoutUrlOf,
  entitlementOf,
  isInformationalBillingEvent,
} from "@/billing";
import { checkoutRedirect } from "@/adapters/next";
import { Yoon } from "@/Yoon";
import { YoonWebhookEvent } from "@/Webhook";
import { EventType } from "../generated/src/models/index";
import { json } from "./helpers";

const CHECKOUT = "https://pay.example.com/checkout/pay_1?t=tok";

const INVOICE_OPEN = {
  id: "inv_1",
  object: "invoice",
  number: "INV-0001",
  customer: "cus_1",
  subscription: "sub_1",
  kind: "subscription_create",
  status: "open",
  amount_due: 5000,
  currency: "XOF",
  lines: [],
  checkout_url: CHECKOUT,
  created_at: "2026-10-01T00:00:00Z",
};

function subscription(overrides: Record<string, unknown> = {}) {
  return {
    id: "sub_1",
    object: "subscription",
    customer: { id: "cus_1", external_id: "user_1" },
    price: { id: "price_1", currency: "XOF", amount: 5000, interval: "month", interval_count: 1, plan: { id: "plan_1", slug: "pro", name: "Pro" } },
    status: "active",
    collection_method: "prepaid_manual",
    current_period_start: "2026-10-01T00:00:00Z",
    current_period_end: "2026-11-01T00:00:00Z",
    grace_period_days: 3,
    cancel_at_period_end: false,
    comp: false,
    entitled: true,
    features: { seats: 5, api: true },
    latest_invoice: INVOICE_OPEN,
    metadata: {},
    created_at: "2026-10-01T00:00:00Z",
    updated_at: "2026-10-01T00:00:00Z",
    ...overrides,
  };
}

describe("entitlementOf", () => {
  it("an active subscription is entitled until its period end", () => {
    expect(entitlementOf(subscription())).toEqual({
      entitled: true,
      plan: "pro",
      until: new Date("2026-11-01T00:00:00Z"),
      features: { seats: 5, api: true },
      status: "active",
    });
  });

  it("past_due is entitled (grace)", () => {
    expect(entitlementOf(subscription({ status: "past_due" })).entitled).toBe(true);
  });

  it.each(["incomplete", "expired", "canceled"])("%s is not entitled", (status) => {
    expect(entitlementOf(subscription({ status, entitled: false })).entitled).toBe(false);
  });

  it("an open-ended comp has until = null", () => {
    const e = entitlementOf(subscription({ comp: true, current_period_end: null }));
    expect(e.entitled).toBe(true);
    expect(e.until).toBeNull();
  });

  it("an unknown status does not throw and is not entitled, even if the server says entitled", () => {
    const e = entitlementOf(subscription({ status: "paused_somehow", entitled: true }));
    expect(e).toMatchObject({ entitled: false, status: "paused_somehow", plan: "pro" });
  });

  it("tolerates a missing or malformed object", () => {
    expect(entitlementOf({})).toEqual({ entitled: false, plan: null, until: null, features: {}, status: "" });
    expect(entitlementOf({ status: 42, price: "x", features: [], current_period_end: "not a date" })).toEqual({
      entitled: false,
      plan: null,
      until: null,
      features: {},
      status: "",
    });
  });

  it("reads a webhook's event.object", () => {
    const event = YoonWebhookEvent.fromJson(
      JSON.stringify({ id: "evt_1", type: "subscription.renewed", created_at: "x", data: { object: subscription() } }),
    );
    expect(changesEntitlement(event.type)).toBe(true);
    expect(entitlementOf(event.object)).toMatchObject({ entitled: true, plan: "pro" });
  });
});

describe("billing event types", () => {
  it("classifies every subscription and invoice event type of the contract", () => {
    const billing = Object.values(EventType).filter((t) => t.startsWith("subscription.") || t.startsWith("invoice."));
    for (const type of billing) {
      expect(changesEntitlement(type) !== isInformationalBillingEvent(type)).toBe(true);
    }
    expect([...ENTITLEMENT_EVENT_TYPES].sort()).toEqual(
      [
        "subscription.activated",
        "subscription.canceled",
        "subscription.changed",
        "subscription.expired",
        "subscription.past_due",
        "subscription.renewed",
      ],
    );
    expect(INFORMATIONAL_BILLING_EVENT_TYPES.has("subscription.renewal_upcoming")).toBe(true);
    expect(INFORMATIONAL_BILLING_EVENT_TYPES.has("invoice.paid")).toBe(true);
  });

  it("unknown or non-string types are neither, and do not throw", () => {
    for (const type of ["subscription.paused", "payment.succeeded", "", undefined, null, 7]) {
      expect(changesEntitlement(type)).toBe(false);
      expect(isInformationalBillingEvent(type)).toBe(false);
    }
  });
});

describe("checkoutUrlOf / checkoutRedirect", () => {
  it("redirects with 303 to a subscription's latest invoice checkout", () => {
    const res = checkoutRedirect(subscription({ status: "incomplete" }));
    expect(res.status).toBe(303);
    expect(res.headers.get("location")).toBe(CHECKOUT);
  });

  it("redirects to an invoice's checkout", () => {
    expect(checkoutRedirect(INVOICE_OPEN).headers.get("location")).toBe(CHECKOUT);
  });

  function reason(fn: () => unknown): string {
    try {
      fn();
    } catch (error) {
      expect(error).toBeInstanceOf(NoCheckoutError);
      return (error as NoCheckoutError).reason;
    }
    throw new Error("expected NoCheckoutError");
  }

  it("refuses a paid invoice", () => {
    expect(reason(() => checkoutUrlOf({ ...INVOICE_OPEN, status: "paid", checkout_url: null }))).toBe("paid");
  });

  it("an open invoice without checkout tells the caller to renew", () => {
    const sub = subscription({ latest_invoice: { ...INVOICE_OPEN, checkout_url: null } });
    expect(() => checkoutRedirect(sub)).toThrow(/renew/);
    expect(reason(() => checkoutUrlOf(sub))).toBe("checkout_unavailable");
  });

  it("refuses a subscription without an invoice, and a void one", () => {
    expect(reason(() => checkoutUrlOf(subscription({ latest_invoice: null })))).toBe("no_invoice");
    expect(reason(() => checkoutUrlOf({ ...INVOICE_OPEN, status: "void", checkout_url: null }))).toBe("void");
  });

  it("an unknown invoice status does not crash the parser and gives the raw status", () => {
    expect(reason(() => checkoutUrlOf({ ...INVOICE_OPEN, status: "disputed", checkout_url: null }))).toBe("disputed");
  });
});

describe("Yoon billing helpers", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("createSubscription posts with the idempotency key; renewSubscription returns the invoice", async () => {
    const calls: Array<[string, RequestInit]> = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string, init: RequestInit) => {
        calls.push([String(url), init]);
        return String(url).endsWith("/renew") ? json(INVOICE_OPEN) : json(subscription({ status: "incomplete" }), 201);
      }),
    );
    const yoon = new Yoon("https://pay.example.com", "yk_test");
    const sub = await yoon.createSubscription({ customer: "cus_1", price: "price_1" }, "sub-user_1");
    expect(sub.status).toBe("incomplete");
    expect(checkoutUrlOf(sub)).toBe(CHECKOUT);
    expect(calls[0]![0]).toBe("https://pay.example.com/v1/subscriptions");
    expect(new Headers(calls[0]![1].headers).get("idempotency-key")).toBe("sub-user_1");

    const invoice = await yoon.renewSubscription("sub_1", "renew-sub_1-2026-11", "https://shop.example/back");
    expect(invoice.checkout_url).toBe(CHECKOUT);
    expect(calls[1]![0]).toBe("https://pay.example.com/v1/subscriptions/sub_1/renew");
    expect(JSON.parse(String(calls[1]![1].body))).toEqual({ return_url: "https://shop.example/back" });
  });

  it("parses an unknown subscription status without throwing", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => json(subscription({ status: "frozen" }))));
    const sub = await new Yoon("https://pay.example.com", "yk_test").getSubscription("sub_1");
    expect(sub.status).toBe("frozen");
    expect(entitlementOf(sub).entitled).toBe(false);
  });

  it("exposes the generated customers, plans and subscriptions APIs", () => {
    const yoon = new Yoon("https://pay.example.com", "yk_test");
    expect(typeof yoon.customers().listCustomers).toBe("function");
    expect(typeof yoon.plans().listPlans).toBe("function");
    expect(typeof yoon.subscriptions().listInvoices).toBe("function");
  });
});
