import "reflect-metadata";
import express from "express";
import request from "supertest";
import { describe, expect, it } from "vitest";
import { Test } from "@nestjs/testing";
import { YOON_CLIENT, YoonModule, YoonWebhookMiddleware } from "@/adapters/nest";
import { Yoon } from "@/Yoon";
import { EVENT_BODY, SECRET, T, sign } from "./helpers";

function buildApp(middleware: YoonWebhookMiddleware, options?: { fail?: boolean }) {
  const events: string[] = [];
  let handled = 0;
  const app = express();
  app.post(
    "/yoon/webhook",
    express.raw({ type: "*/*" }),
    (req, res, next) => middleware.use(req, res, next),
    (_req, res) => {
      handled += 1;
      if (options?.fail) {
        res.sendStatus(500);
        return;
      }
      events.push("handled");
      res.sendStatus(200);
    },
  );
  return { app, events, handledCount: () => handled };
}

describe("nest adapter", () => {
  it("provides the Yoon client through DI", async () => {
    const moduleRef = await Test.createTestingModule({
      imports: [
        YoonModule.forRoot({ url: "https://pay.example.com", apiKey: "yk_test", webhookSecret: SECRET, now: () => T }),
      ],
    }).compile();
    expect(moduleRef.get(YOON_CLIENT)).toBeInstanceOf(Yoon);
    expect(moduleRef.get(YoonWebhookMiddleware)).toBeInstanceOf(YoonWebhookMiddleware);
  });

  it("behaves like the express middleware: verify, dedupe, remember only on 2xx", async () => {
    const moduleRef = await Test.createTestingModule({
      imports: [
        YoonModule.forRoot({ url: "https://pay.example.com", apiKey: "yk_test", webhookSecret: SECRET, now: () => T }),
      ],
    }).compile();
    const middleware = moduleRef.get(YoonWebhookMiddleware);
    const app = buildApp(middleware);

    const first = await request(app.app)
      .post("/yoon/webhook")
      .set("content-type", "application/json")
      .set("yoon-signature", sign(SECRET, EVENT_BODY, T))
      .send(EVENT_BODY);
    expect(first.status).toBe(200);
    expect(app.events).toEqual(["handled"]);

    const dup = await request(app.app)
      .post("/yoon/webhook")
      .set("content-type", "application/json")
      .set("yoon-signature", sign(SECRET, EVENT_BODY, T))
      .send(EVENT_BODY);
    expect(dup.status).toBe(200);
    expect(dup.body).toEqual({ duplicate: true });
    expect(app.handledCount()).toBe(1);
  });

  it("refuses a bad signature with 401", async () => {
    const moduleRef = await Test.createTestingModule({
      imports: [
        YoonModule.forRoot({ url: "https://pay.example.com", apiKey: "yk_test", webhookSecret: SECRET, now: () => T }),
      ],
    }).compile();
    const app = buildApp(moduleRef.get(YoonWebhookMiddleware));
    const res = await request(app.app)
      .post("/yoon/webhook")
      .set("content-type", "application/json")
      .set("yoon-signature", sign("whsec_wrong", EVENT_BODY, T))
      .send(EVENT_BODY);
    expect(res.status).toBe(401);
    expect(app.handledCount()).toBe(0);
  });

  it("refuses to build the middleware without a webhook secret", async () => {
    const testingModule = Test.createTestingModule({
      imports: [YoonModule.forRoot({ url: "https://pay.example.com", apiKey: "yk_test" })],
    });
    await expect(testingModule.compile()).rejects.toThrow(/webhookSecret/);
  });
});
