import { afterEach, describe, expect, it } from "vitest";
import { readConfig } from "../src/config";
import { BASE, KEY, connect, json, mockYoon, text } from "./helpers";

afterEach(async () => {
  const { vi } = await import("vitest");
  vi.unstubAllGlobals();
});

const ABOUT = { version: "0.6.0", application: "shop" };
const PROVIDERS = [{ provider: "wave", countries: ["SN"], methods: ["wave"] }];

describe("config", () => {
  it("needs YOON_URL and YOON_API_KEY, without echoing values", () => {
    expect(() => readConfig({})).toThrow(/YOON_URL and YOON_API_KEY/);
    expect(() => readConfig({ YOON_URL: BASE })).toThrow(/YOON_API_KEY/);
    try {
      readConfig({ YOON_API_KEY: KEY });
    } catch (error) {
      expect(String(error)).not.toContain(KEY);
    }
  });

  it("turns writes and contacts on only with an explicit true", () => {
    const base = { YOON_URL: BASE, YOON_API_KEY: KEY };
    expect(readConfig(base)).toMatchObject({ writes: false, showContacts: false });
    expect(readConfig({ ...base, YOON_MCP_WRITES: "1" }).writes).toBe(false);
    expect(readConfig({ ...base, YOON_MCP_WRITES: "TRUE", YOON_MCP_SHOW_CONTACTS: "true" })).toMatchObject({
      writes: true,
      showContacts: true,
    });
  });
});

describe("about", () => {
  it("is listed as read-only and returns the gateway and its providers", async () => {
    const fetchMock = mockYoon({ "GET /v1/about": () => json(ABOUT), "GET /v1/providers": () => json(PROVIDERS) });
    const client = await connect();

    const { tools } = await client.listTools();
    expect(tools.map((t) => t.name)).toContain("about");
    expect(tools.find((t) => t.name === "about")!.annotations?.readOnlyHint).toBe(true);

    const result = await client.callTool({ name: "about", arguments: {} });
    expect(result.isError).toBeFalsy();
    expect(JSON.parse(text(result))).toMatchObject({ about: ABOUT, providers: [{ provider: "wave" }] });
    const providersCall = fetchMock.mock.calls.find((c) => String(c[0]).endsWith("/v1/providers"))!;
    expect(new Headers((providersCall[1] as RequestInit).headers).get("authorization")).toBe(`Bearer ${KEY}`);
  });

  it("reports Yoon's problem code as a tool error", async () => {
    mockYoon({
      "GET /v1/about": () => json({ code: "unauthorized", status: 401 }, 401),
      "GET /v1/providers": () => json(PROVIDERS),
    });
    const result = await (await connect()).callTool({ name: "about", arguments: {} });
    expect(result.isError).toBe(true);
    expect(JSON.parse(text(result))).toMatchObject({ error: "unauthorized", http_status: 401, retryable: false });
  });
});

describe("the API key", () => {
  it("never appears in a tool result, even if a provider error message carries it", async () => {
    mockYoon({
      "GET /v1/about": () => json({ code: "provider_error", status: 502, detail: `upstream echoed Bearer ${KEY}` }, 502),
      "GET /v1/providers": () => json(PROVIDERS),
    });
    const result = await (await connect()).callTool({ name: "about", arguments: {} });
    expect(result.isError).toBe(true);
    expect(text(result)).not.toContain(KEY);
    expect(text(result)).toContain("[redacted]");
  });

  it("is not in a network failure either", async () => {
    mockYoon({
      "GET /v1/about": () => {
        throw new TypeError(`fetch failed for Bearer ${KEY}`);
      },
      "GET /v1/providers": () => json(PROVIDERS),
    });
    const result = await (await connect()).callTool({ name: "about", arguments: {} });
    expect(result.isError).toBe(true);
    expect(text(result)).not.toContain(KEY);
  });
});

describe("version", () => {
  it("matches package.json; the JS client dependency is a published 0.x release", async () => {
    const { readFileSync } = await import("node:fs");
    const { VERSION } = await import("../src/server");
    const pkg = JSON.parse(readFileSync(new URL("../package.json", import.meta.url), "utf8"));
    expect(VERSION).toBe(pkg.version);
    // The MCP server only needs what the client already had; it depends on a version that is
    // on npm before the release tag, so CI and the release can install it.
    expect(pkg.dependencies["@yoonpay/yoon"]).toMatch(/^\^0\.\d+\.0$/);
  });
});
