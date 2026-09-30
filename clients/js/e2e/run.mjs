// The shared e2e scenario (clients/e2e/scenario.md) against a real Yoon server.
// Usage: YOON_URL=http://localhost:8080 YOON_API_KEY=yk_… node clients/js/e2e/run.mjs
// Run `npm run build` in clients/js first: the script imports the built package.
import { Yoon, YoonException } from "../dist/index.js";

const url = process.env.YOON_URL;
const apiKey = process.env.YOON_API_KEY;
if (!url || !apiKey) {
  console.error("Set YOON_URL and YOON_API_KEY (see clients/e2e/scenario.md).");
  process.exit(1);
}

let step = 0;
function ok(name) {
  step += 1;
  console.log(`  ${step}. ${name}`);
}
function fail(name, error) {
  console.error(`  ✗ step ${step + 1}: ${name}`);
  console.error(error);
  process.exit(1);
}

const yoon = new Yoon(url, apiKey);
const reference = `e2e_${Date.now()}`;
const request = {
  amount: 5000,
  currency: "XOF",
  country: "SN",
  method: "wave",
  customer: { phone: "+221771234567" },
  reference,
};

// 1. createPayment → pending, phone masked
let payment;
try {
  payment = await yoon.createPayment(request, reference);
  if (payment.status !== "pending") throw new Error(`status ${payment.status}, expected pending`);
  if (!/^\+\d+\*\*\*\d+$/.test(payment.customer?.phone ?? "")) {
    throw new Error(`phone not masked: ${payment.customer?.phone}`);
  }
  ok(`createPayment → ${payment.status}, phone masked (${payment.customer.phone})`);
} catch (error) {
  fail("createPayment", error);
}

// 2. same idempotency key → the same payment id
try {
  const replay = await yoon.createPayment(request, reference);
  if (replay.id !== payment.id) throw new Error(`replay returned ${replay.id}, expected ${payment.id}`);
  ok("same idempotency key → same payment id");
} catch (error) {
  fail("idempotent replay", error);
}

// 3. getPayment, then list by reference → found
try {
  const fetched = await yoon.getPayment(payment.id);
  if (fetched.id !== payment.id) throw new Error("getPayment returned another payment");
  const listed = await yoon.payments().listPayments({ reference });
  if (!listed.data.some((p) => p.id === payment.id)) throw new Error("payment not found by reference");
  ok("getPayment + list by reference → found");
} catch (error) {
  fail("get/list", error);
}

// 4. refund of a pending payment → payment_not_refundable
try {
  const error = await yoon.refund(payment.id, `${reference}_refund`).catch((e) => e);
  if (!(error instanceof YoonException) || error.problemCode !== "payment_not_refundable") {
    throw new Error(`expected problemCode payment_not_refundable, got ${error.problemCode}`);
  }
  ok("refund of pending payment → payment_not_refundable");
} catch (error) {
  fail("refund", error);
}

// 5. bad API key → unauthorized
try {
  const intruder = new Yoon(url, "yk_not_a_real_key");
  const error = await intruder.getPayment(payment.id).catch((e) => e);
  if (!(error instanceof YoonException) || error.problemCode !== "unauthorized") {
    throw new Error(`expected problemCode unauthorized, got ${error.problemCode}`);
  }
  ok("bad API key → unauthorized");
} catch (error) {
  fail("bad key", error);
}

// 6. exportCsv('payments') → starts with id,created_at
try {
  const csv = await yoon.exportCsv("payments");
  if (!csv.startsWith("id,created_at")) throw new Error(`unexpected CSV header: ${csv.split("\n")[0]}`);
  ok("exportCsv → id,created_at…");
} catch (error) {
  fail("exportCsv", error);
}

// 7. createPayout → processing
try {
  const payout = await yoon.createPayout(
    {
      amount: 2000,
      currency: "XOF",
      country: "SN",
      method: "wave",
      recipient: { phone: "+221771234567" },
      reference: `${reference}_payout`,
    },
    `${reference}_payout`,
  );
  if (payout.status !== "processing") throw new Error(`status ${payout.status}, expected processing`);
  ok("createPayout → processing");
} catch (error) {
  fail("createPayout", error);
}

console.log(`e2e: all ${step} steps passed against ${url}`);
