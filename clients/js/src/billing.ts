/**
 * Framework-free helpers for prepaid subscriptions (Yoon 0.3.0, ADR-0026/0027).
 *
 * Response enums are an open set: a newer Yoon may send a status or event type this client does
 * not know. Nothing here throws on one — an unknown status is simply not entitled, and an unknown
 * event type neither changes entitlement nor counts as informational.
 */

/** Statuses that give access: `active`, and `past_due` (renewal unpaid, still in grace). */
export const ENTITLED_STATUSES: ReadonlySet<string> = new Set(["active", "past_due"]);

/** Event types after which a customer's access may have changed: re-read `entitlementOf(event.object)`. */
export const ENTITLEMENT_EVENT_TYPES: ReadonlySet<string> = new Set([
  "subscription.activated",
  "subscription.renewed",
  "subscription.changed",
  "subscription.past_due",
  "subscription.expired",
  "subscription.canceled",
]);

/**
 * Billing event types that never change access: reminders and invoice bookkeeping (and
 * `subscription.created`, which starts `incomplete`, i.e. not entitled). Use them to send
 * messages ("your plan renews in 3 days", a receipt link), not to grant or revoke.
 */
export const INFORMATIONAL_BILLING_EVENT_TYPES: ReadonlySet<string> = new Set([
  "subscription.created",
  "subscription.renewal_upcoming",
  "invoice.created",
  "invoice.paid",
  "invoice.voided",
]);

/** True for the event types listed in {@link ENTITLEMENT_EVENT_TYPES}; false for anything else, unknown types included. */
export function changesEntitlement(eventType: unknown): boolean {
  return typeof eventType === "string" && ENTITLEMENT_EVENT_TYPES.has(eventType);
}

/** True for the event types listed in {@link INFORMATIONAL_BILLING_EVENT_TYPES}; false for anything else. */
export function isInformationalBillingEvent(eventType: unknown): boolean {
  return typeof eventType === "string" && INFORMATIONAL_BILLING_EVENT_TYPES.has(eventType);
}

/** The subset of a subscription these helpers read. A `Subscription` or a webhook's `event.object` both fit. */
export interface SubscriptionLike {
  status?: unknown;
  current_period_end?: unknown;
  features?: unknown;
  price?: unknown;
  latest_invoice?: unknown;
}

export interface Entitlement {
  /** Whether the customer should have access now: status `active` or `past_due` (grace). */
  entitled: boolean;
  /** The plan's slug (`price.plan.slug`), or null if absent. */
  plan: string | null;
  /**
   * End of the paid period (`current_period_end`). When `entitled`, null means an open-ended
   * operator comp (no end date). When not entitled, it is informational only.
   */
  until: Date | null;
  /** The plan's features, as copied onto the subscription (empty object if absent). */
  features: Record<string, unknown>;
  /** The raw status, passed through even when this client does not know it. */
  status: string;
}

/**
 * Reads access from a subscription (an API response or a webhook's `event.object`). Mirrors the
 * server's `entitled` rule from the status, so an unknown status is not entitled. It does not
 * compare dates against the clock: Yoon's billing sweep moves the status at period end.
 */
export function entitlementOf(subscription: SubscriptionLike): Entitlement {
  const status = typeof subscription?.status === "string" ? subscription.status : "";
  const features = isRecord(subscription?.features) ? subscription.features : {};
  const price = isRecord(subscription?.price) ? subscription.price : undefined;
  const planRef = price && isRecord(price.plan) ? price.plan : undefined;
  const slug = planRef && typeof planRef.slug === "string" ? planRef.slug : null;
  return {
    entitled: ENTITLED_STATUSES.has(status),
    plan: slug,
    until: parseDate(subscription?.current_period_end),
    features,
    status,
  };
}

/**
 * Thrown by {@link checkoutUrlOf} when there is nothing to pay at a checkout page. In CommonJS the
 * main entry and `/next` each bundle their own copy, so test `error.name === "NoCheckoutError"`
 * (or `reason`) rather than `instanceof` across entry points.
 */
export class NoCheckoutError extends Error {
  /** `paid`, `void`, `no_invoice`, `checkout_unavailable` (open but no usable checkout: renew), or the unknown status. */
  readonly reason: string;

  constructor(reason: string, message: string) {
    super(message);
    this.name = "NoCheckoutError";
    this.reason = reason;
  }
}

/**
 * The `checkout_url` to send the customer to, from an invoice or a subscription (its
 * `latest_invoice`). Throws {@link NoCheckoutError} when there is none: the invoice is paid or
 * void, or its checkout expired — then call `POST /v1/subscriptions/{id}/renew`
 * (`yoon.renewSubscription`) for a fresh one.
 */
export function checkoutUrlOf(invoiceOrSubscription: Record<string, unknown> | object): string {
  const source = invoiceOrSubscription as Record<string, unknown>;
  const isSubscription = source?.object === "subscription" || "latest_invoice" in (source ?? {});
  const invoice = isSubscription ? source.latest_invoice : source;
  if (!isRecord(invoice)) {
    throw new NoCheckoutError("no_invoice", "This subscription has no invoice to pay; call renew to create one.");
  }
  if (typeof invoice.checkout_url === "string" && invoice.checkout_url.length > 0) {
    return invoice.checkout_url;
  }
  const status = typeof invoice.status === "string" ? invoice.status : "";
  if (status === "paid") {
    throw new NoCheckoutError("paid", "This invoice is already paid: there is nothing to check out.");
  }
  if (status === "open") {
    throw new NoCheckoutError(
      "checkout_unavailable",
      "This invoice has no usable checkout (it probably expired); call renew to get a fresh checkout_url.",
    );
  }
  if (status === "void") {
    throw new NoCheckoutError("void", "This invoice is void; call renew to create a new one.");
  }
  throw new NoCheckoutError(status || "checkout_unavailable", `This invoice (status ${status || "unknown"}) has no checkout_url.`);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function parseDate(value: unknown): Date | null {
  if (value instanceof Date) return Number.isNaN(value.getTime()) ? null : value;
  if (typeof value !== "string" || value.length === 0) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}
