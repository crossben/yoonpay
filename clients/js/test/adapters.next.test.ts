import { describe, expect, it } from "vitest";
import { verifyWebhook } from "@/verifyWebhook";
import { yoonWebhookRoute } from "@/adapters/next";
import { MemoryEventStore } from "@/WebhookReceiver";
import { EVENT_BODY, SECRET, T, sign } from "./helpers";

function delivery(body: string, signature: string) {
  return new Request("https://shop.example/yoon/webhook", {
    method: "POST",
    headers: { "content-type": "application/json", "yoon-signature": signature },
    body,
  });
}

// §4.4 against the framework-free core (Next.js route handlers and Hono-style apps
// use exactly this path).
describe("verifyWebhook / yoonWebhookRoute", () => {
  it("delivers a valid event and remembers it across a shared store", async () => {
    const store = new MemoryEventStore();
    const events: string[] = [];
    const route = yoonWebhookRoute({
      secret: SECRET,
      store,
      now: () => T,
      onEvent: (event) => {
        events.push(event.id);
      },
    });

    const first = await route(delivery(EVENT_BODY, sign(SECRET, EVENT_BODY, T)));
    expect(first.status).toBe(200);
    expect(await first.json()).toMatchObject({ received: true });
    expect(events).toEqual(["evt_0199a3f0c2e47a1b9c3d5e6f7a8b9c0d"]);

    const dup = await route(delivery(EVENT_BODY, sign(SECRET, EVENT_BODY, T)));
    expect(dup.status).toBe(200);
    expect(await dup.json()).toEqual({ duplicate: true });
    expect(events).toHaveLength(1);
  });

  it("remembers events without an explicit store (options built per request)", async () => {
    // The common Next.js / edge usage: options written inline in the handler, no store.
    const events: string[] = [];
    const handle = (request: Request) =>
      verifyWebhook(request, { secret: SECRET, now: () => T, onEvent: (e) => void events.push(e.id) });

    const body = EVENT_BODY.replace("evt_0199a3f0c2e47a1b9c3d5e6f7a8b9c0d", "evt_default_store_inline");
    await handle(delivery(body, sign(SECRET, body, T)));
    const dup = await handle(delivery(body, sign(SECRET, body, T)));

    expect(await dup.json()).toEqual({ duplicate: true });
    expect(events).toEqual(["evt_default_store_inline"]);
  });

  it("the Next.js route remembers events without an explicit store", async () => {
    const events: string[] = [];
    const route = yoonWebhookRoute({ secret: SECRET, now: () => T, onEvent: (e) => void events.push(e.id) });

    const body = EVENT_BODY.replace("evt_0199a3f0c2e47a1b9c3d5e6f7a8b9c0d", "evt_default_store_route");
    await route(delivery(body, sign(SECRET, body, T)));
    const dup = await route(delivery(body, sign(SECRET, body, T)));

    expect(await dup.json()).toEqual({ duplicate: true });
    expect(events).toEqual(["evt_default_store_route"]);
  });

  it("acknowledges without echoing the event back", async () => {
    const body = EVENT_BODY.replace("evt_0199a3f0c2e47a1b9c3d5e6f7a8b9c0d", "evt_no_echo");
    const res = await verifyWebhook(delivery(body, sign(SECRET, body, T)), {
      secret: SECRET,
      store: new MemoryEventStore(),
      now: () => T,
      onEvent: () => {},
    });
    expect(await res.json()).toEqual({ received: true });
  });

  it("rejects a bad signature with 401", async () => {
    const res = await verifyWebhook(delivery(EVENT_BODY, sign("whsec_wrong", EVENT_BODY, T)), {
      secret: SECRET,
      now: () => T,
      onEvent: () => {},
    });
    expect(res.status).toBe(401);
    expect(await res.json()).toEqual({ error: "invalid signature" });
  });

  it("answers 400 for a signed body that is not a Yoon event", async () => {
    const body = '{"hello":"world"}';
    const res = await verifyWebhook(delivery(body, sign(SECRET, body, T)), {
      secret: SECRET,
      now: () => T,
      onEvent: () => {},
    });
    expect(res.status).toBe(400);
    expect(await res.json()).toEqual({ error: "not a Yoon event" });
  });

  it("a failing handler is not remembered: the retry reaches it", async () => {
    let calls = 0;
    const options = {
      secret: SECRET,
      store: new MemoryEventStore(),
      now: () => T,
      onEvent: () => {
        calls += 1;
        throw new Error("down");
      },
    };
    const first = await verifyWebhook(delivery(EVENT_BODY, sign(SECRET, EVENT_BODY, T)), options);
    expect(first.status).toBe(500);
    const retry = await verifyWebhook(delivery(EVENT_BODY, sign(SECRET, EVENT_BODY, T)), options);
    expect(retry.status).toBe(500);
    expect(calls).toBe(2);
  });
});
