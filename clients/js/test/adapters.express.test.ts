import express from "express";
import request from "supertest";
import { beforeEach, describe, expect, it } from "vitest";
import { yoonWebhook } from "@/adapters/express";
import { EVENT_BODY, SECRET, T, sign } from "./helpers";

function buildApp(options?: { jsonParser?: boolean; fail?: boolean }) {
  const events: string[] = [];
  let handled = 0;
  const app = express();
  if (options?.jsonParser) app.use(express.json());
  else app.use(express.raw({ type: "*/*" }));
  app.post(
    "/yoon/webhook",
    yoonWebhook({ secret: SECRET, now: () => T }),
    (req, res) => {
      handled += 1;
      if (options?.fail) {
        res.sendStatus(500);
        return;
      }
      events.push(req.yoonEvent!.id);
      res.sendStatus(200);
    },
  );
  return { app, events, handledCount: () => handled };
}

function auth(body: string) {
  return { "content-type": "application/json", "yoon-signature": sign(SECRET, body, T) };
}

// §4.4: valid event reaches the handler; bad signature → 401; duplicate → 200 without the
// handler; a failing handler (500) is not remembered, so the retry reaches it.
describe("express adapter", () => {
  let app: ReturnType<typeof buildApp>;
  beforeEach(() => {
    app = buildApp();
  });

  it("delivers a valid event to the handler and remembers it", async () => {
    const res = await request(app.app).post("/yoon/webhook").set(auth(EVENT_BODY)).send(EVENT_BODY);
    expect(res.status).toBe(200);
    expect(app.events).toEqual(["evt_0199a3f0c2e47a1b9c3d5e6f7a8b9c0d"]);

    // the same delivery again: answered 200 without reaching the handler
    const dup = await request(app.app).post("/yoon/webhook").set(auth(EVENT_BODY)).send(EVENT_BODY);
    expect(dup.status).toBe(200);
    expect(dup.body).toEqual({ duplicate: true });
    expect(app.handledCount()).toBe(1);
  });

  it("rejects a bad signature with 401 without calling the handler", async () => {
    const res = await request(app.app)
      .post("/yoon/webhook")
      .set("content-type", "application/json")
      .set("yoon-signature", sign("whsec_wrong", EVENT_BODY, T))
      .send(EVENT_BODY);
    expect(res.status).toBe(401);
    expect(res.body).toEqual({ error: "invalid signature" });
    expect(app.handledCount()).toBe(0);
  });

  it("rejects a stale timestamp with 401", async () => {
    const stale = sign(SECRET, EVENT_BODY, T - 301);
    const res = await request(app.app)
      .post("/yoon/webhook")
      .set("content-type", "application/json")
      .set("yoon-signature", stale)
      .send(EVENT_BODY);
    expect(res.status).toBe(401);
  });

  it("does not remember a failing handler: the retry reaches it", async () => {
    const failing = buildApp({ fail: true });
    const first = await request(failing.app).post("/yoon/webhook").set(auth(EVENT_BODY)).send(EVENT_BODY);
    expect(first.status).toBe(500);
    expect(failing.handledCount()).toBe(1);

    const retry = await request(failing.app).post("/yoon/webhook").set(auth(EVENT_BODY)).send(EVENT_BODY);
    expect(retry.status).toBe(500);
    expect(failing.handledCount()).toBe(2); // delivered again
  });

  it("refuses a parsed-then-re-encoded body (the raw-body pitfall)", async () => {
    const parsedApp = buildApp({ jsonParser: true });
    // The body contains a space after the colon; express.json() parses it and the
    // middleware re-encodes without the space — the bytes no longer match the signature.
    const spacedBody = EVENT_BODY.replace('{"id"', '{ "id"');
    const res = await request(parsedApp.app)
      .post("/yoon/webhook")
      .set("content-type", "application/json")
      .set("yoon-signature", sign(SECRET, spacedBody, T))
      .send(spacedBody);
    expect(res.status).toBe(401);
    expect(parsedApp.handledCount()).toBe(0);
  });

  it("reads the raw stream when no body parser ran", async () => {
    const events: string[] = [];
    const bare = express();
    bare.post(
      "/yoon/webhook",
      yoonWebhook({ secret: SECRET, now: () => T }),
      (req, res) => {
        events.push(req.yoonEvent!.id);
        res.sendStatus(200);
      },
    );
    const res = await request(bare).post("/yoon/webhook").set(auth(EVENT_BODY)).send(EVENT_BODY);
    expect(res.status).toBe(200);
    expect(events).toEqual(["evt_0199a3f0c2e47a1b9c3d5e6f7a8b9c0d"]);
  });
});
