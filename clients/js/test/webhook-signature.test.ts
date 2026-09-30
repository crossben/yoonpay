import { describe, expect, it } from "vitest";
import { verifySignature, YoonWebhookEvent, sign } from "@/Webhook";
import { EVENT_BODY, SECRET, T, signatureVector, sign as signVector } from "./helpers";

// §4.1: every client's unit tests load api/test-vectors/webhook-signature.json.
describe("verifySignature — shared test vector", () => {
  it("verifies the vector", async () => {
    await expect(
      verifySignature(signatureVector.secret, signatureVector.header, signatureVector.body, signatureVector.timestamp),
    ).resolves.toBe(true);
  });

  it("rejects a tampered body", async () => {
    const tampered = signatureVector.body.slice(0, -2) + '"}';
    await expect(
      verifySignature(signatureVector.secret, signatureVector.header, tampered, signatureVector.timestamp),
    ).resolves.toBe(false);
  });

  it("rejects a wrong secret", async () => {
    await expect(
      verifySignature("whsec_wrong", signatureVector.header, signatureVector.body, signatureVector.timestamp),
    ).resolves.toBe(false);
  });

  it("rejects a timestamp more than 300 s off, accepts 300 s", async () => {
    await expect(
      verifySignature(signatureVector.secret, signatureVector.header, signatureVector.body, signatureVector.timestamp + 301),
    ).resolves.toBe(false);
    await expect(
      verifySignature(signatureVector.secret, signatureVector.header, signatureVector.body, signatureVector.timestamp + 300),
    ).resolves.toBe(true);
    await expect(
      verifySignature(signatureVector.secret, signatureVector.header, signatureVector.body, signatureVector.timestamp - 300),
    ).resolves.toBe(true);
  });
});

describe("verifySignature", () => {
  it("verifies a signed delivery and works with byte bodies", async () => {
    const header = await sign(SECRET, EVENT_BODY, T);
    await expect(verifySignature(SECRET, header, EVENT_BODY, T)).resolves.toBe(true);
    await expect(verifySignature(SECRET, header, new TextEncoder().encode(EVENT_BODY), T)).resolves.toBe(true);
  });

  it("rejects malformed headers", async () => {
    for (const header of ["", "v1=deadbeef", "t=abc,v1=deadbeef", "garbage", "t=123", null, undefined]) {
      await expect(verifySignature(SECRET, header as never, EVENT_BODY, T)).resolves.toBe(false);
    }
  });

  it("rejects an empty secret", async () => {
    await expect(verifySignature("", signVector(SECRET, EVENT_BODY, T), EVENT_BODY, T)).resolves.toBe(false);
  });

  it("ignores extra header parts around the t/v1 pair", async () => {
    const header = await sign(SECRET, EVENT_BODY, T);
    await expect(verifySignature(SECRET, `whsec=1, ${header}, v2=x`, EVENT_BODY, T)).resolves.toBe(true);
  });

  it("rejects an upper-case v1 key, like the PHP and Java clients", async () => {
    const header = (await sign(SECRET, EVENT_BODY, T)).replace("v1=", "V1=");
    await expect(verifySignature(SECRET, header, EVENT_BODY, T)).resolves.toBe(false);
  });
});

describe("YoonWebhookEvent", () => {
  it("exposes id, type and object", () => {
    const event = YoonWebhookEvent.fromJson(EVENT_BODY);
    expect(event.id).toBe("evt_0199a3f0c2e47a1b9c3d5e6f7a8b9c0d");
    expect(event.type).toBe("payment.succeeded");
    expect(event.object.status).toBe("succeeded");
    expect(event.object.amount).toBe(5000);
  });

  it("rejects bodies that are not Yoon events", () => {
    expect(() => YoonWebhookEvent.fromJson("{}")).toThrow("Not a Yoon event");
    expect(() => YoonWebhookEvent.fromJson("{\"id\":\"e\",\"type\":\"t\"}")).toThrow("Not a Yoon event");
    expect(() => YoonWebhookEvent.fromJson("not json")).toThrow("Not a Yoon event");
  });
});
