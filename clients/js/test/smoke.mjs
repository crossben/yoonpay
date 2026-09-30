// Smoke test of the BUILT package under plain `node` — run after `npm run build`.
// Vitest and `node -e` expose globals (e.g. `crypto` on Node 18) that real applications don't
// get, so they cannot prove the runtime floor; this file can.
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { Yoon, verifySignature, sign } from "../dist/index.js";

const vector = JSON.parse(
  readFileSync(new URL("../../../api/test-vectors/webhook-signature.json", import.meta.url), "utf8"),
);
const checks = {
  "ESM loads": typeof Yoon === "function",
  "CJS loads": typeof createRequire(import.meta.url)("../dist/index.cjs").Yoon === "function",
  "signature vector verifies (Web Crypto available)": await verifySignature(
    vector.secret,
    vector.header,
    vector.body,
    vector.timestamp,
  ),
  "sign() reproduces the vector": (await sign(vector.secret, vector.body, vector.timestamp)) === vector.header,
  "client constructs": new Yoon("https://pay.example.com", "yk_smoke").toString() === "Yoon(https://pay.example.com)",
};
const failed = Object.entries(checks).filter(([, ok]) => !ok);
for (const [name, ok] of Object.entries(checks)) console.log(`${ok ? "ok  " : "FAIL"} ${name}`);
if (failed.length) {
  console.error(`smoke: ${failed.length} check(s) failed on Node ${process.version}`);
  process.exit(1);
}
console.log(`smoke: all checks passed on Node ${process.version}`);
