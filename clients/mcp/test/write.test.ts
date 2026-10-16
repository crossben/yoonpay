import { afterEach, describe, expect, it, vi } from "vitest";
import { connect, json, mockYoon, text } from "./helpers";

afterEach(() => vi.unstubAllGlobals());

const WRITE_TOOLS = ["create_payment_link", "create_portal_session", "redeliver_event"];
const LINK = {
  id: "plink_1",
  object: "payment_link",
  url: "https://pay.example.com/l/abc",
  qr_url: "https://pay.example.com/l/abc/qr.svg",
  amount: 5000,
  currency: "XOF",
  country: "SN",
  description: "Tote bag",
  active: true,
  payments_count: 0,
  amount_collected: 0,
  created_at: "2026-10-16T10:00:00Z",
};

const requestOf = (fetchMock: ReturnType<typeof mockYoon>, i = 0) => {
  const [url, init] = fetchMock.mock.calls[i]! as [string, RequestInit];
  return { url: new URL(url), init, headers: new Headers(init.headers), body: init.body ? JSON.parse(String(init.body)) : null };
};

describe("write tools", () => {
  it("are not listed, and cannot be called, without YOON_MCP_WRITES", async () => {
    const fetchMock = mockYoon({ "POST /v1/payment_links": () => json(LINK, 201) });
    const client = await connect();
    const names = (await client.listTools()).tools.map((t) => t.name);
    for (const name of WRITE_TOOLS) expect(names).not.toContain(name);

    const result = await client
      .callTool({
        name: "create_payment_link",
        arguments: { idempotency_key: "link-0001", description: "x", currency: "XOF", country: "SN", amount: 1 },
      })
      .catch((error: unknown) => ({ isError: true, error }));
    expect(result.isError).toBe(true);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("with YOON_MCP_WRITES: exactly three, none moving money out, all non-destructive and idempotent", async () => {
    mockYoon({});
    const { tools } = await (await connect({ writes: true })).listTools();
    const writes = tools.filter((t) => t.annotations?.readOnlyHint === false);
    expect(writes.map((t) => t.name).sort()).toEqual(WRITE_TOOLS);
    for (const t of writes) {
      expect(t.annotations).toMatchObject({ destructiveHint: false, idempotentHint: true });
      expect(t.inputSchema.required).toContain("idempotency_key");
    }
    const all = tools.map((t) => t.name).join(" ");
    expect(all).not.toMatch(/refund_|payout_|cancel|comp|coupon|create_payment\b|create_payout/);
  });

  it("create_payment_link sends the caller's idempotency key and the link fields", async () => {
    const fetchMock = mockYoon({ "POST /v1/payment_links": () => json(LINK, 201) });
    const client = await connect({ writes: true });
    const result = await client.callTool({
      name: "create_payment_link",
      arguments: {
        idempotency_key: "link-tote-bag-m",
        description: "Tote bag",
        currency: "xof",
        country: "sn",
        amount: 5000,
        max_payments: 1,
      },
    });
    expect(result.isError).toBeFalsy();
    const out = JSON.parse(text(result));
    expect(out.url).toBe(LINK.url);
    expect(out.untrusted_text.description).toBe("Tote bag");

    const req = requestOf(fetchMock);
    expect(req.init.method).toBe("POST");
    expect(req.headers.get("idempotency-key")).toBe("link-tote-bag-m");
    expect(req.body).toMatchObject({ description: "Tote bag", currency: "XOF", country: "SN", amount: 5000, max_payments: 1 });
  });

  it("refuses a write without an idempotency key, before calling Yoon", async () => {
    const fetchMock = mockYoon({ "POST /v1/payment_links": () => json(LINK, 201) });
    const result = await (await connect({ writes: true })).callTool({
      name: "create_payment_link",
      arguments: { description: "Tote bag", currency: "XOF", country: "SN", amount: 5000 },
    });
    expect(result.isError).toBe(true);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("a retried call sends the same key (Yoon then returns the same link)", async () => {
    const fetchMock = mockYoon({ "POST /v1/payment_links": () => json(LINK, 201) });
    const client = await connect({ writes: true });
    const args = { idempotency_key: "link-retry-1", description: "Tote bag", currency: "XOF", country: "SN", amount: 5000 };
    await client.callTool({ name: "create_payment_link", arguments: args });
    await client.callTool({ name: "create_payment_link", arguments: args });
    expect(requestOf(fetchMock, 0).headers.get("idempotency-key")).toBe("link-retry-1");
    expect(requestOf(fetchMock, 1).headers.get("idempotency-key")).toBe("link-retry-1");
  });

  it("create_portal_session is view-and-pay only: no permissions are granted", async () => {
    const fetchMock = mockYoon({
      "POST /v1/customers/cus_1/portal_sessions": () =>
        json({ id: "ps_1", object: "portal_session", customer: "cus_1", url: "https://pay.example.com/checkout/portal/ps_1?t=x", expires_at: "2026-10-16T11:00:00Z" }, 201),
    });
    const result = await (await connect({ writes: true })).callTool({
      name: "create_portal_session",
      arguments: { idempotency_key: "portal-cus1-01", customer_id: "cus_1", return_url: "https://shop.example/account", allow: { cancel: true } },
    });
    expect(result.isError).toBeFalsy();
    const req = requestOf(fetchMock);
    expect(req.headers.get("idempotency-key")).toBe("portal-cus1-01");
    expect(req.body.return_url).toBe("https://shop.example/account");
    expect(req.body.allow ?? null).toBeNull();
  });

  it("redeliver_event posts to the event's redeliver route", async () => {
    const fetchMock = mockYoon({
      "POST /v1/events/evt_1/redeliver": () => json({ id: "evt_1", object: "event", type: "payment.succeeded" }, 202),
    });
    const result = await (await connect({ writes: true })).callTool({
      name: "redeliver_event",
      arguments: { idempotency_key: "redeliver-evt1", id: "evt_1" },
    });
    expect(result.isError).toBeFalsy();
    expect(requestOf(fetchMock).headers.get("idempotency-key")).toBe("redeliver-evt1");
  });
});
