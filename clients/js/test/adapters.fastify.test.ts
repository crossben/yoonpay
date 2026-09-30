import Fastify from "fastify";
import { describe, expect, it } from "vitest";
import { yoonWebhook } from "@/adapters/fastify";
import { EVENT_BODY, SECRET, T, sign } from "./helpers";

function buildApp(options?: { fail?: boolean }) {
  const events: string[] = [];
  let handled = 0;
  const app = Fastify();
  app.register(yoonWebhook, {
    secret: SECRET,
    now: () => T,
    onEvent: (event) => {
      handled += 1;
      if (options?.fail) throw new Error("handler failed");
      events.push(event.id);
    },
  });
  return { app, events, handledCount: () => handled };
}

function post(app: ReturnType<typeof buildApp>["app"], body: string, signature: string) {
  return app.inject({
    method: "POST",
    url: "/yoon/webhook",
    payload: body,
    headers: { "content-type": "application/json", "yoon-signature": signature },
  });
}

describe("fastify adapter", () => {
  it("delivers a valid event, remembers it, answers duplicates without onEvent", async () => {
    const app = buildApp();
    const first = await post(app.app, EVENT_BODY, sign(SECRET, EVENT_BODY, T));
    expect(first.statusCode).toBe(200);
    expect(first.json()).toMatchObject({ received: true });
    expect(app.events).toEqual(["evt_0199a3f0c2e47a1b9c3d5e6f7a8b9c0d"]);

    const dup = await post(app.app, EVENT_BODY, sign(SECRET, EVENT_BODY, T));
    expect(dup.statusCode).toBe(200);
    expect(dup.json()).toEqual({ duplicate: true });
    expect(app.handledCount()).toBe(1);
  });

  it("rejects a bad signature with 401", async () => {
    const app = buildApp();
    const res = await post(app.app, EVENT_BODY, sign("whsec_wrong", EVENT_BODY, T));
    expect(res.statusCode).toBe(401);
    expect(res.json()).toEqual({ error: "invalid signature" });
    expect(app.handledCount()).toBe(0);
  });

  it("a failing handler is not remembered: the retry reaches it", async () => {
    const app = buildApp({ fail: true });
    const first = await post(app.app, EVENT_BODY, sign(SECRET, EVENT_BODY, T));
    expect(first.statusCode).toBe(500);
    const retry = await post(app.app, EVENT_BODY, sign(SECRET, EVENT_BODY, T));
    expect(retry.statusCode).toBe(500);
    expect(app.handledCount()).toBe(2);
  });

  it("keeps the app's own JSON parser outside the webhook scope", async () => {
    const app = Fastify();
    let otherBody: unknown;
    app.post("/other", async (request) => {
      otherBody = request.body;
      return { ok: true };
    });
    app.register(yoonWebhook, { secret: SECRET, now: () => T, onEvent: () => {} });
    const parsed = await app.inject({
      method: "POST",
      url: "/other",
      payload: { hello: "world" },
      headers: { "content-type": "application/json" },
    });
    expect(parsed.statusCode).toBe(200);
    expect(otherBody).toEqual({ hello: "world" }); // parsed object, not a Buffer
  });
});
