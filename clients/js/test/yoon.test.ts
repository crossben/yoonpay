import { afterEach, describe, expect, it, vi } from "vitest";
import { Yoon } from "@/Yoon";
import { YoonException } from "@/YoonException";
import { PAYMENT_PENDING, json } from "./helpers";

const BASE = "https://pay.example.com";
const KEY = "yk_secret_key_value";

const PAYMENT_REQUEST = {
  amount: 5000,
  currency: "XOF",
  country: "SN",
  method: "wave",
  customer: { phone: "+221771234567" },
  reference: "order_1042",
};

function mockFetch(handler: (url: string, init: RequestInit) => Response | Promise<Response>) {
  const fn = vi.fn(async (url: string | URL | Request, init?: RequestInit) =>
    handler(String(url), init ?? {}),
  );
  vi.stubGlobal("fetch", fn);
  return fn;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("Yoon — request shape", () => {
  it("sends POST /v1/payments with Authorization, Idempotency-Key, User-Agent", async () => {
    const fetchMock = mockFetch(() => json(PAYMENT_PENDING, 201));
    const yoon = new Yoon(BASE, KEY);
    const payment = await yoon.createPayment(PAYMENT_REQUEST, "order-1042");

    expect(payment.id).toBe(PAYMENT_PENDING.id);
    expect(payment.status).toBe("pending");
    const [url, init] = fetchMock.mock.calls[0]! as [string, RequestInit];
    expect(url).toBe(`${BASE}/v1/payments`);
    expect(init.method).toBe("POST");
    const headers = new Headers(init.headers);
    expect(headers.get("authorization")).toBe(`Bearer ${KEY}`);
    expect(headers.get("idempotency-key")).toBe("order-1042");
    expect(headers.get("user-agent")).toBe("yoon-javascript/0.2.0");
    expect(headers.get("content-type")).toContain("application/json");
    expect(JSON.parse(String(init.body))).toMatchObject({ amount: 5000, reference: "order_1042" });
  });

  it("tolerates a trailing slash in baseUrl", async () => {
    const fetchMock = mockFetch(() => json(PAYMENT_PENDING));
    const yoon = new Yoon(`${BASE}/`, KEY);
    await yoon.getPayment("pay_1");
    expect(fetchMock.mock.calls[0]![0]).toBe(`${BASE}/v1/payments/pay_1`);
  });

  it("requires the idempotency key on writes (never generated silently)", async () => {
    mockFetch(() => json(PAYMENT_PENDING, 201));
    const yoon = new Yoon(BASE, KEY);
    // @ts-expect-error — the key is required at compile time; the runtime must refuse too.
    await expect(yoon.createPayment(PAYMENT_REQUEST, undefined)).rejects.toThrow(/Idempotency_Key/);
  });

  it("sends CSV exports as GET with from/to", async () => {
    const fetchMock = mockFetch(
      () => new Response("id,created_at\n1,2026-09-30T10:00:00Z", { status: 200, headers: { "content-type": "text/csv" } }),
    );
    const yoon = new Yoon(BASE, KEY);
    const csv = await yoon.exportCsv("payments", "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z");
    expect(csv.startsWith("id,created_at")).toBe(true);
    const [url, init] = fetchMock.mock.calls[0]! as [string, RequestInit];
    expect(url).toBe(
      `${BASE}/v1/exports/payments.csv?from=2026-09-01T00%3A00%3A00Z&to=2026-10-01T00%3A00%3A00Z`,
    );
    expect(init.method).toBe("GET");
  });
});

describe("Yoon — errors", () => {
  it("maps 422 problem+json to the stable code (not retryable)", async () => {
    mockFetch(() =>
      json({ type: "about:blank", title: "No provider", status: 422, code: "no_provider_for_method" }, 422),
    );
    const yoon = new Yoon(BASE, KEY);
    const error = await yoon.createPayment(PAYMENT_REQUEST, "k1").catch((e) => e);
    expect(error).toBeInstanceOf(YoonException);
    expect(error.problemCode).toBe("no_provider_for_method");
    expect(error.httpStatus).toBe(422);
    expect(error.isRetryable()).toBe(false);
    expect(error.problem).toMatchObject({ code: "no_provider_for_method" });
  });

  it("maps 409 idempotency_in_progress to retryable", async () => {
    mockFetch(() => json({ code: "idempotency_in_progress" }, 409));
    const yoon = new Yoon(BASE, KEY);
    const error = await yoon.createPayment(PAYMENT_REQUEST, "k1").catch((e) => e);
    expect(error.problemCode).toBe("idempotency_in_progress");
    expect(error.isRetryable()).toBe(true);
  });

  it("maps a 500 without a problem document to a retryable null code", async () => {
    mockFetch(() => new Response("<html>bad gateway</html>", { status: 500, headers: { "content-type": "text/html" } }));
    const yoon = new Yoon(BASE, KEY);
    const error = await yoon.getPayment("pay_1").catch((e) => e);
    expect(error.problemCode).toBeNull();
    expect(error.httpStatus).toBe(500);
    expect(error.isRetryable()).toBe(true);
  });

  it("maps a network failure to a retryable null code", async () => {
    mockFetch(() => Promise.reject(new TypeError("fetch failed: ECONNREFUSED")));
    const yoon = new Yoon(BASE, KEY);
    const error = await yoon.getPayment("pay_1").catch((e) => e);
    expect(error).toBeInstanceOf(YoonException);
    expect(error.problemCode).toBeNull();
    expect(error.isRetryable()).toBe(true);
    expect(error.message).toContain("Yoon unreachable");
  });

  it("maps a timeout to a retryable null code", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(
        (_url: string | URL | Request, init?: RequestInit) =>
          new Promise<Response>((_resolve, reject) => {
            init?.signal?.addEventListener("abort", () => reject(init.signal!.reason));
          }),
      ),
    );
    const yoon = new Yoon(BASE, KEY, { timeoutMs: 25 });
    const error = await yoon.getPayment("pay_1").catch((e) => e);
    expect(error).toBeInstanceOf(YoonException);
    expect(error.problemCode).toBeNull();
    expect(error.isRetryable()).toBe(true);
    expect(error.message).toContain("timed out");
  });

  it("maps a connect timeout distinctly", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(
        (_url: string | URL | Request, init?: RequestInit) =>
          new Promise<Response>((resolve, reject) => {
            init?.signal?.addEventListener("abort", () => reject(init.signal!.reason));
            setTimeout(() => resolve(json(PAYMENT_PENDING)), 250);
          }),
      ),
    );
    const yoon = new Yoon(BASE, KEY, { connectTimeoutMs: 20, timeoutMs: 5_000 });
    const error = await yoon.getPayment("pay_1").catch((e) => e);
    expect(error).toBeInstanceOf(YoonException);
    expect(error.isRetryable()).toBe(true);
    expect(error.message).toContain("connection timed out");
  });

  it("reports an unreadable 2xx as unreadable_response (not retryable)", async () => {
    mockFetch(() => new Response("<html>not json</html>", { status: 200, headers: { "content-type": "text/html" } }));
    const yoon = new Yoon(BASE, KEY);
    const error = await yoon.getPayment("pay_1").catch((e) => e);
    expect(error.problemCode).toBe("unreadable_response");
    expect(error.httpStatus).toBe(200);
    expect(error.isRetryable()).toBe(false);
  });
});

describe("Yoon — contract pitfalls", () => {
  it("deserializes OpenAPI 3.1 nullables to null", async () => {
    mockFetch(() => json({ ...PAYMENT_PENDING, reference: null, description: null, checkout_url: null }));
    const yoon = new Yoon(BASE, KEY);
    const payment = await yoon.getPayment("pay_1");
    expect(payment.reference).toBeNull();
    expect(payment.description).toBeNull();
    expect(payment.checkout_url).toBeNull();
  });

  it("does not crash on an unknown enum value from a newer server", async () => {
    mockFetch(() => json({ ...PAYMENT_PENDING, status: "settled_instantly" }));
    const yoon = new Yoon(BASE, KEY);
    const payment = await yoon.getPayment("pay_1");
    expect(payment.status).toBe("settled_instantly");
  });
});

describe("Yoon — construction", () => {
  it("refuses empty baseUrl and apiKey without echoing them", () => {
    expect(() => new Yoon("", KEY)).toThrow(/baseUrl/);
    expect(() => new Yoon(BASE, "")).toThrow(/apiKey/);
  });

  it("refuses to run in a browser unless allowed", () => {
    vi.stubGlobal("window", { document: {} });
    expect(() => new Yoon(BASE, KEY)).toThrow(/browser/);
    expect(() => new Yoon(BASE, KEY, { dangerouslyAllowBrowser: true })).not.toThrow();
  });

  it("never leaks the API key in errors, stacks or toString", async () => {
    mockFetch(() => json({ code: "unauthorized" }, 401));
    const yoon = new Yoon(BASE, KEY);
    const error = await yoon.createPayment(PAYMENT_REQUEST, "k1").catch((e) => e);
    expect(String(error)).not.toContain(KEY);
    expect(error.stack).not.toContain(KEY);
    expect(String(yoon)).toBe(`Yoon(${BASE})`);
    expect(String(yoon)).not.toContain(KEY);
  });
});
