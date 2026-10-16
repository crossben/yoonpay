import {
  Configuration,
  CustomersApi,
  EventsApi,
  ExportsApi,
  LedgerApi,
  MetaApi,
  PaymentsApi,
  PayoutsApi,
  PlansApi,
  RefundsApi,
  SubscriptionsApi,
  CouponsApi,
  PaymentLinksApi,
  type CreateCustomerRequest,
  type CreatePaymentRequest,
  type CreateSubscriptionRequest,
  type CreatePortalSessionRequestAllow,
  type PortalSession,
  type Customer,
  type Invoice,
  type CreatePayoutRequest,
  type Middleware,
  type Payment,
  type Payout,
  type Refund,
  type Subscription,
} from "../generated/src/index";
import { YoonException } from "./YoonException";
import { timedFetch } from "./internal/timedFetch";

/** Client version, carried in the User-Agent (`yoon-javascript/<version>`). */
export const CLIENT_VERSION = "0.7.0";
export const USER_AGENT = `yoon-javascript/${CLIENT_VERSION}`;

const DEFAULT_TIMEOUT_MS = 30_000;
const DEFAULT_CONNECT_TIMEOUT_MS = 5_000;

export interface YoonOptions {
  /** Whole-exchange timeout in milliseconds (default 30 000). */
  timeoutMs?: number;
  /** Time until the response headers must arrive, in milliseconds (default 5 000). */
  connectTimeoutMs?: number;
  /** Replace `fetch` (tests, proxies, custom agents). Must not retry. */
  fetchApi?: typeof fetch;
  /**
   * The API key is a server secret. Constructing the client in a browser leaks it to
   * anyone who opens devtools; pass `dangerouslyAllowBrowser` only if you understand that.
   */
  dangerouslyAllowBrowser?: boolean;
}

export type YoonExport = "payments" | "refunds" | "payouts" | "ledger";

/**
 * Entry point. Common calls have helpers; every operation of the API contract is available
 * on the generated API objects ({@link Yoon.payments}, {@link Yoon.events}, …), wrapped with
 * {@link Yoon.call} to get {@link YoonException}s. The client never retries: the caller
 * retries with the same idempotency key.
 *
 * Every create call takes an idempotency key: use something tied to your intent (your order
 * id) so that a retry after a timeout returns the original result instead of charging twice.
 */
export class Yoon {
  readonly baseUrl: string;

  private readonly configuration: Configuration;
  private paymentsApi?: PaymentsApi;
  private refundsApi?: RefundsApi;
  private payoutsApi?: PayoutsApi;
  private eventsApi?: EventsApi;
  private ledgerApi?: LedgerApi;
  private metaApi?: MetaApi;
  private exportsApi?: ExportsApi;
  private customersApi?: CustomersApi;
  private plansApi?: PlansApi;
  private subscriptionsApi?: SubscriptionsApi;
  private couponsApi?: CouponsApi;
  private paymentLinksApi?: PaymentLinksApi;

  constructor(baseUrl: string, apiKey: string, options: YoonOptions = {}) {
    if (typeof baseUrl !== "string" || baseUrl.length === 0) {
      throw new Error("baseUrl must be a non-empty string, e.g. https://pay.example.com");
    }
    if (typeof apiKey !== "string" || apiKey.length === 0) {
      throw new Error("apiKey must be a non-empty string (yk_…)");
    }
    if (isBrowser() && !options.dangerouslyAllowBrowser) {
      throw new Error(
        "Yoon looks like it is running in a browser, where the API key would be public. " +
          "Call Yoon from your backend, or pass dangerouslyAllowBrowser to accept the risk.",
      );
    }

    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.slice(0, -1) : baseUrl;

    // Detects a 2xx this client cannot read before the generated code tries to parse it
    // (the runtime hands `post` a clone, so the original body is untouched).
    const unreadable: Middleware = {
      async post(context) {
        if (!context.response.ok) return;
        const type = context.response.headers.get("content-type") ?? "";
        if (type.includes("csv")) return; // the generated ExportsApi reads it as text
        if (type.includes("json")) {
          try {
            JSON.parse(await context.response.clone().text());
          } catch (cause) {
            throw YoonException.unreadable(context.response.status, cause);
          }
          return;
        }
        // A 2xx in an unexpected format (e.g. a proxy's HTML page) is not a readable answer.
        throw YoonException.unreadable(
          context.response.status,
          new Error(`content-type was ${type || "none"}`),
        );
      },
    };

    this.configuration = new Configuration({
      basePath: this.baseUrl,
      accessToken: () => apiKey,
      headers: { "User-Agent": USER_AGENT },
      fetchApi: timedFetch(
        options.fetchApi ?? ((...args) => fetch(...args)),
        options.timeoutMs ?? DEFAULT_TIMEOUT_MS,
        options.connectTimeoutMs ?? DEFAULT_CONNECT_TIMEOUT_MS,
      ),
      middleware: [unreadable],
    });
  }

  createPayment(payment: CreatePaymentRequest, idempotencyKey: string): Promise<Payment> {
    return this.call(() => this.payments().createPayment({ Idempotency_Key: idempotencyKey, CreatePaymentRequest: payment }));
  }

  /**
   * A customer portal session (Yoon 0.6+): send the customer to `.url` right away. `allow` adds
   * actions beyond viewing and paying. A new session per click is intended, so the idempotency key
   * defaults to a random one.
   */
  createPortalSession(
    customerId: string,
    options: { returnUrl?: string; allow?: CreatePortalSessionRequestAllow } = {},
    idempotencyKey: string = `portal-${crypto.randomUUID()}`,
  ): Promise<PortalSession> {
    return this.call(() =>
      this.customers().createPortalSession({
        id: customerId,
        Idempotency_Key: idempotencyKey,
        CreatePortalSessionRequest: { return_url: options.returnUrl, allow: options.allow },
      }),
    );
  }

  getPayment(id: string): Promise<Payment> {
    return this.call(() => this.payments().getPayment({ id }));
  }

  /** `amount` omitted refunds whatever is left. */
  refund(paymentId: string, idempotencyKey: string, amount?: number, reason?: string): Promise<Refund> {
    return this.call(() =>
      this.refunds().createRefund({
        id: paymentId,
        Idempotency_Key: idempotencyKey,
        CreateRefundRequest: { amount, reason },
      }),
    );
  }

  createPayout(payout: CreatePayoutRequest, idempotencyKey: string): Promise<Payout> {
    return this.call(() => this.payouts().createPayout({ Idempotency_Key: idempotencyKey, CreatePayoutRequest: payout }));
  }

  createCustomer(customer: CreateCustomerRequest, idempotencyKey: string): Promise<Customer> {
    return this.call(() =>
      this.customers().createCustomer({ Idempotency_Key: idempotencyKey, CreateCustomerRequest: customer }),
    );
  }

  /**
   * Starts a prepaid subscription. It is `incomplete` until its first invoice is paid: send the
   * customer to `latest_invoice.checkout_url` (see `checkoutUrlOf`) and grant access from the
   * webhook, never from the return URL.
   */
  createSubscription(subscription: CreateSubscriptionRequest, idempotencyKey: string): Promise<Subscription> {
    return this.call(() =>
      this.subscriptions().createSubscription({
        Idempotency_Key: idempotencyKey,
        CreateSubscriptionRequest: subscription,
      }),
    );
  }

  getSubscription(id: string): Promise<Subscription> {
    return this.call(() => this.subscriptions().getSubscription({ id }));
  }

  /**
   * The invoice for the next period, with a usable `checkout_url` (the open one if there is
   * already one, with a fresh checkout if the last one expired).
   */
  renewSubscription(id: string, idempotencyKey: string, returnUrl?: string): Promise<Invoice> {
    return this.call(() =>
      this.subscriptions().renewSubscription({
        id,
        Idempotency_Key: idempotencyKey,
        RenewSubscriptionRequest: { return_url: returnUrl },
      }),
    );
  }

  /** CSV export (amounts in minor units, UTC timestamps, phones masked). */
  exportCsv(kind: YoonExport, from?: string, to?: string): Promise<string> {
    return this.call(() => {
      const parameters = { from, to };
      switch (kind) {
        case "payments":
          return this.exports().exportPayments(parameters);
        case "refunds":
          return this.exports().exportRefunds(parameters);
        case "payouts":
          return this.exports().exportPayouts(parameters);
        case "ledger":
          return this.exports().exportLedger(parameters);
      }
    });
  }

  /** Runs any generated API call, turning Yoon's problem+json errors into {@link YoonException}s. */
  async call<T>(fn: () => T | Promise<T>): Promise<T> {
    try {
      return await fn();
    } catch (error) {
      if (error instanceof YoonException) throw error;
      const mapped = await YoonException.fromGeneratedError(error);
      if (mapped) throw mapped;
      throw error;
    }
  }

  payments(): PaymentsApi {
    return (this.paymentsApi ??= new PaymentsApi(this.configuration));
  }

  refunds(): RefundsApi {
    return (this.refundsApi ??= new RefundsApi(this.configuration));
  }

  payouts(): PayoutsApi {
    return (this.payoutsApi ??= new PayoutsApi(this.configuration));
  }

  events(): EventsApi {
    return (this.eventsApi ??= new EventsApi(this.configuration));
  }

  ledger(): LedgerApi {
    return (this.ledgerApi ??= new LedgerApi(this.configuration));
  }

  meta(): MetaApi {
    return (this.metaApi ??= new MetaApi(this.configuration));
  }

  customers(): CustomersApi {
    return (this.customersApi ??= new CustomersApi(this.configuration));
  }

  /** Plans and their prices. */
  plans(): PlansApi {
    return (this.plansApi ??= new PlansApi(this.configuration));
  }

  /** Subscriptions and their invoices (`getInvoice`, `listInvoices`). */
  /** Coupons (Yoon 0.4+): percent or amount off, redeemed on subscriptions. */
  coupons(): CouponsApi {
    return (this.couponsApi ??= new CouponsApi(this.configuration));
  }

  /** Payment links (Yoon 0.5+): a shareable URL and QR code that opens a hosted checkout. */
  paymentLinks(): PaymentLinksApi {
    return (this.paymentLinksApi ??= new PaymentLinksApi(this.configuration));
  }

  subscriptions(): SubscriptionsApi {
    return (this.subscriptionsApi ??= new SubscriptionsApi(this.configuration));
  }

  private exports(): ExportsApi {
    return (this.exportsApi ??= new ExportsApi(this.configuration));
  }

  /** Never contains the API key. */
  toString(): string {
    return `Yoon(${this.baseUrl})`;
  }
}

function isBrowser(): boolean {
  return typeof window !== "undefined" && typeof window.document !== "undefined";
}
