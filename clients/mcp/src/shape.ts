/**
 * What the assistant sees. Three rules (ADR-0032):
 * - contacts (phone, e-mail, PI-SPI alias) are masked unless the merchant turned that off;
 * - text written by customers or third parties moves under `untrusted_text`: data, never
 *   instructions;
 * - every object with a currency says how many decimals its amounts have.
 */
const CONTACT_KEYS = new Set(["phone", "email", "pi_alias", "customer_phone"]);
const UNTRUSTED_KEYS = new Set(["description", "metadata", "name", "message", "reason", "detail", "raw_status", "note"]);

// ISO 4217 minor units for the currencies Yoon's providers use; anything else: 2.
const ZERO_DECIMALS = new Set(["XOF", "XAF", "GNF", "RWF", "KMF", "BIF", "DJF", "UGX", "JPY", "KRW"]);

export function shape(value: unknown, showContacts: boolean): unknown {
  return walk(JSON.parse(JSON.stringify(value ?? null)), showContacts);
}

function walk(value: unknown, showContacts: boolean): unknown {
  if (Array.isArray(value)) return value.map((v) => walk(v, showContacts));
  if (value === null || typeof value !== "object") return value;
  const out: Record<string, unknown> = {};
  const untrusted: Record<string, unknown> = {};
  for (const [key, v] of Object.entries(value)) {
    if (UNTRUSTED_KEYS.has(key)) {
      if (v !== null && v !== undefined) untrusted[key] = v;
    } else if (CONTACT_KEYS.has(key) && typeof v === "string" && !showContacts) {
      out[key] = key === "email" ? maskEmail(v) : maskPhone(v);
    } else {
      out[key] = walk(v, showContacts);
    }
  }
  if (typeof out.currency === "string") out.currency_decimals = ZERO_DECIMALS.has(out.currency) ? 0 : 2;
  if (Object.keys(untrusted).length > 0) out.untrusted_text = untrusted;
  return out;
}

export function maskPhone(phone: string): string {
  if (phone.includes("*")) return phone; // Yoon already masked it
  const digits = phone.replace(/[^\d+]/g, "");
  if (digits.length < 7) return "***";
  return `${digits.slice(0, digits.startsWith("+") ? 6 : 5)}***${digits.slice(-2)}`;
}

export function maskEmail(email: string): string {
  const at = email.lastIndexOf("@");
  if (at < 1) return "***";
  return `${email[0]}***${email.slice(at)}`;
}
