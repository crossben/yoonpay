import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { vi } from "vitest";
import { createServer } from "../src/server";
import type { McpConfig } from "../src/config";

export const KEY = "yk_test_assistant_key_value";
export const BASE = "https://pay.example.com";

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": status >= 400 ? "application/problem+json" : "application/json" },
  });
}

/** Routes fetch by "METHOD /path" (query string ignored); unknown routes are a 404 problem. */
export function mockYoon(routes: Record<string, (url: URL) => Response>) {
  const fn = vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
    const url = new URL(String(input));
    const handler = routes[`${init?.method ?? "GET"} ${url.pathname}`];
    return handler ? handler(url) : json({ code: "not_found", status: 404 }, 404);
  });
  vi.stubGlobal("fetch", fn);
  return fn;
}

export async function connect(overrides: Partial<McpConfig> = {}) {
  const config: McpConfig = { url: BASE, apiKey: KEY, writes: false, showContacts: false, ...overrides };
  const [clientSide, serverSide] = InMemoryTransport.createLinkedPair();
  await createServer(config).connect(serverSide);
  const client = new Client({ name: "test", version: "0" });
  await client.connect(clientSide);
  return client;
}

export function text(result: object): string {
  const content = (result as { content: unknown }).content as Array<{ type: string; text: string }>;
  return content.map((c) => c.text).join("\n");
}
