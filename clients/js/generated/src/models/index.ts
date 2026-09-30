/* tslint:disable */
/* eslint-disable */
/**
 * 
 * @export
 * @interface AdminReplayDeadLetter202Response
 */
export interface AdminReplayDeadLetter202Response {
    /**
     * 
     * @type {string}
     * @memberof AdminReplayDeadLetter202Response
     */
    id: string;
    /**
     * 
     * @type {string}
     * @memberof AdminReplayDeadLetter202Response
     */
    delivery_status: AdminReplayDeadLetter202ResponseDeliveryStatusEnum;
}


/**
 * @export
 */
export const AdminReplayDeadLetter202ResponseDeliveryStatusEnum = {
    Pending: 'pending'
} as const;
export type AdminReplayDeadLetter202ResponseDeliveryStatusEnum = typeof AdminReplayDeadLetter202ResponseDeliveryStatusEnum[keyof typeof AdminReplayDeadLetter202ResponseDeliveryStatusEnum];

/**
 * 
 * @export
 * @interface AdminResolvePayoutRequest
 */
export interface AdminResolvePayoutRequest {
    /**
     * 
     * @type {string}
     * @memberof AdminResolvePayoutRequest
     */
    status: AdminResolvePayoutRequestStatusEnum;
    /**
     * 
     * @type {string}
     * @memberof AdminResolvePayoutRequest
     */
    note: string;
}


/**
 * @export
 */
export const AdminResolvePayoutRequestStatusEnum = {
    Paid: 'paid',
    Failed: 'failed'
} as const;
export type AdminResolvePayoutRequestStatusEnum = typeof AdminResolvePayoutRequestStatusEnum[keyof typeof AdminResolvePayoutRequestStatusEnum];

/**
 * 
 * @export
 * @interface CreatePaymentRequest
 */
export interface CreatePaymentRequest {
    /**
     * Minor units.
     * @type {number}
     * @memberof CreatePaymentRequest
     */
    amount: number;
    /**
     * 
     * @type {string}
     * @memberof CreatePaymentRequest
     */
    currency: string;
    /**
     * 
     * @type {string}
     * @memberof CreatePaymentRequest
     */
    country: string;
    /**
     * e.g. wave, orange_money, card
     * @type {string}
     * @memberof CreatePaymentRequest
     */
    method: string;
    /**
     * 
     * @type {CreatePaymentRequestCustomer}
     * @memberof CreatePaymentRequest
     */
    customer?: CreatePaymentRequestCustomer;
    /**
     * Your own order id.
     * @type {string}
     * @memberof CreatePaymentRequest
     */
    reference?: string;
    /**
     * 
     * @type {string}
     * @memberof CreatePaymentRequest
     */
    description?: string;
    /**
     * 
     * @type {string}
     * @memberof CreatePaymentRequest
     */
    return_url?: string;
    /**
     * Optional: pin a configured provider instead of routing.
     * @type {string}
     * @memberof CreatePaymentRequest
     */
    provider?: string;
}
/**
 * 
 * @export
 * @interface CreatePaymentRequestCustomer
 */
export interface CreatePaymentRequestCustomer {
    /**
     * Local or international; validated for `country`.
     * @type {string}
     * @memberof CreatePaymentRequestCustomer
     */
    phone?: string;
    /**
     * The customer's PI-SPI payment alias. Required by the `pispi` provider.
     * @type {string}
     * @memberof CreatePaymentRequestCustomer
     */
    pi_alias?: string;
}
/**
 * 
 * @export
 * @interface CreatePayoutRequest
 */
export interface CreatePayoutRequest {
    /**
     * 
     * @type {number}
     * @memberof CreatePayoutRequest
     */
    amount: number;
    /**
     * 
     * @type {string}
     * @memberof CreatePayoutRequest
     */
    currency: string;
    /**
     * 
     * @type {string}
     * @memberof CreatePayoutRequest
     */
    country: string;
    /**
     * 
     * @type {string}
     * @memberof CreatePayoutRequest
     */
    method: string;
    /**
     * 
     * @type {CreatePayoutRequestRecipient}
     * @memberof CreatePayoutRequest
     */
    recipient: CreatePayoutRequestRecipient;
    /**
     * 
     * @type {string}
     * @memberof CreatePayoutRequest
     */
    reference?: string;
    /**
     * Optional: pin a configured provider.
     * @type {string}
     * @memberof CreatePayoutRequest
     */
    provider?: string;
}
/**
 * A phone, or a PI-SPI payment alias (`pispi` provider): at least one.
 * @export
 * @interface CreatePayoutRequestRecipient
 */
export interface CreatePayoutRequestRecipient {
    /**
     * 
     * @type {string}
     * @memberof CreatePayoutRequestRecipient
     */
    phone?: string;
    /**
     * The recipient's PI-SPI payment alias. Required by the `pispi` provider.
     * @type {string}
     * @memberof CreatePayoutRequestRecipient
     */
    pi_alias?: string;
}
/**
 * 
 * @export
 * @interface CreateRefundRequest
 */
export interface CreateRefundRequest {
    /**
     * Omit to refund everything still refundable.
     * @type {number}
     * @memberof CreateRefundRequest
     */
    amount?: number;
    /**
     * 
     * @type {string}
     * @memberof CreateRefundRequest
     */
    reason?: string;
}

/**
 * `no_endpoint` — no webhook URL configured; the event is only available through the API.
 * @export
 */
export const DeliveryStatus = {
    Pending: 'pending',
    Delivered: 'delivered',
    Dead: 'dead',
    NoEndpoint: 'no_endpoint'
} as const;
export type DeliveryStatus = typeof DeliveryStatus[keyof typeof DeliveryStatus];

/**
 * 
 * @export
 * @interface Event
 */
export interface Event {
    /**
     * 
     * @type {string}
     * @memberof Event
     */
    id: string;
    /**
     * 
     * @type {string}
     * @memberof Event
     */
    object: EventObjectEnum;
    /**
     * 
     * @type {EventType}
     * @memberof Event
     */
    type: EventType;
    /**
     * 
     * @type {string}
     * @memberof Event
     */
    resource_type: EventResourceTypeEnum;
    /**
     * 
     * @type {string}
     * @memberof Event
     */
    resource_id: string;
    /**
     * 
     * @type {string}
     * @memberof Event
     */
    created_at: string;
    /**
     * 
     * @type {EventDelivery}
     * @memberof Event
     */
    delivery: EventDelivery;
    /**
     * 
     * @type {EventPayload}
     * @memberof Event
     */
    payload: EventPayload;
}


/**
 * @export
 */
export const EventObjectEnum = {
    Event: 'event'
} as const;
export type EventObjectEnum = typeof EventObjectEnum[keyof typeof EventObjectEnum];

/**
 * @export
 */
export const EventResourceTypeEnum = {
    Payment: 'payment',
    Refund: 'refund',
    Payout: 'payout'
} as const;
export type EventResourceTypeEnum = typeof EventResourceTypeEnum[keyof typeof EventResourceTypeEnum];

/**
 * 
 * @export
 * @interface EventDelivery
 */
export interface EventDelivery {
    /**
     * 
     * @type {DeliveryStatus}
     * @memberof EventDelivery
     */
    status: DeliveryStatus;
    /**
     * 
     * @type {number}
     * @memberof EventDelivery
     */
    attempts: number;
    /**
     * 
     * @type {string}
     * @memberof EventDelivery
     */
    next_attempt_at?: string | null;
    /**
     * 
     * @type {number}
     * @memberof EventDelivery
     */
    last_status_code?: number | null;
    /**
     * 
     * @type {string}
     * @memberof EventDelivery
     */
    last_error?: string | null;
    /**
     * 
     * @type {string}
     * @memberof EventDelivery
     */
    delivered_at?: string | null;
}


/**
 * 
 * @export
 * @interface EventPage
 */
export interface EventPage {
    /**
     * 
     * @type {Array<Event>}
     * @memberof EventPage
     */
    data: Array<Event>;
    /**
     * 
     * @type {boolean}
     * @memberof EventPage
     */
    has_more: boolean;
    /**
     * Pass as `starting_after` to get the next page; null on the last page.
     * @type {string}
     * @memberof EventPage
     */
    next_cursor: string | null;
}
/**
 * The body POSTed to your webhook URL.
 * @export
 * @interface EventPayload
 */
export interface EventPayload {
    /**
     * Deduplicate on this.
     * @type {string}
     * @memberof EventPayload
     */
    id: string;
    /**
     * 
     * @type {string}
     * @memberof EventPayload
     */
    object: EventPayloadObjectEnum;
    /**
     * 
     * @type {EventType}
     * @memberof EventPayload
     */
    type: EventType;
    /**
     * 
     * @type {string}
     * @memberof EventPayload
     */
    created_at: string;
    /**
     * 
     * @type {EventPayloadData}
     * @memberof EventPayload
     */
    data: EventPayloadData;
}


/**
 * @export
 */
export const EventPayloadObjectEnum = {
    Event: 'event'
} as const;
export type EventPayloadObjectEnum = typeof EventPayloadObjectEnum[keyof typeof EventPayloadObjectEnum];

/**
 * 
 * @export
 * @interface EventPayloadData
 */
export interface EventPayloadData {
    /**
     * 
     * @type {EventPayloadDataObject}
     * @memberof EventPayloadData
     */
    object: EventPayloadDataObject;
}
/**
 * @type EventPayloadDataObject
 * The payment, refund or payout as `GET` would return it at the time of the event.
 * @export
 */
export type EventPayloadDataObject = Payment | Payout | Refund;

/**
 * 
 * @export
 */
export const EventType = {
    PaymentSucceeded: 'payment.succeeded',
    PaymentFailed: 'payment.failed',
    PaymentExpired: 'payment.expired',
    RefundSucceeded: 'refund.succeeded',
    RefundFailed: 'refund.failed',
    PayoutPaid: 'payout.paid',
    PayoutFailed: 'payout.failed',
    PayoutNeedsReview: 'payout.needs_review'
} as const;
export type EventType = typeof EventType[keyof typeof EventType];

/**
 * 
 * @export
 * @interface Failure
 */
export interface Failure {
    /**
     * 
     * @type {string}
     * @memberof Failure
     */
    code: string;
    /**
     * 
     * @type {string}
     * @memberof Failure
     */
    message: string;
}
/**
 * 
 * @export
 * @interface GetAbout200Response
 */
export interface GetAbout200Response {
    /**
     * 
     * @type {string}
     * @memberof GetAbout200Response
     */
    name: string;
    /**
     * 
     * @type {string}
     * @memberof GetAbout200Response
     */
    version: string;
    /**
     * 
     * @type {string}
     * @memberof GetAbout200Response
     */
    license: string;
    /**
     * 
     * @type {string}
     * @memberof GetAbout200Response
     */
    source_url: string;
}
/**
 * 
 * @export
 * @interface GetBalances200Response
 */
export interface GetBalances200Response {
    /**
     * 
     * @type {string}
     * @memberof GetBalances200Response
     */
    object: GetBalances200ResponseObjectEnum;
    /**
     * 
     * @type {string}
     * @memberof GetBalances200Response
     */
    note: string;
    /**
     * 
     * @type {Array<GetBalances200ResponseDataInner>}
     * @memberof GetBalances200Response
     */
    data: Array<GetBalances200ResponseDataInner>;
}


/**
 * @export
 */
export const GetBalances200ResponseObjectEnum = {
    Balances: 'balances'
} as const;
export type GetBalances200ResponseObjectEnum = typeof GetBalances200ResponseObjectEnum[keyof typeof GetBalances200ResponseObjectEnum];

/**
 * 
 * @export
 * @interface GetBalances200ResponseDataInner
 */
export interface GetBalances200ResponseDataInner {
    /**
     * 
     * @type {string}
     * @memberof GetBalances200ResponseDataInner
     */
    account: string;
    /**
     * 
     * @type {string}
     * @memberof GetBalances200ResponseDataInner
     */
    provider: string;
    /**
     * 
     * @type {string}
     * @memberof GetBalances200ResponseDataInner
     */
    currency: string;
    /**
     * 
     * @type {number}
     * @memberof GetBalances200ResponseDataInner
     */
    amount: number;
}
/**
 * Positive = debit, negative = credit.
 * @export
 * @interface LedgerEntry
 */
export interface LedgerEntry {
    /**
     * 
     * @type {number}
     * @memberof LedgerEntry
     */
    id: number;
    /**
     * 
     * @type {number}
     * @memberof LedgerEntry
     */
    posting_id: number;
    /**
     * 
     * @type {string}
     * @memberof LedgerEntry
     */
    description: string;
    /**
     * 
     * @type {string}
     * @memberof LedgerEntry
     */
    account: string;
    /**
     * 
     * @type {string}
     * @memberof LedgerEntry
     */
    currency: string;
    /**
     * 
     * @type {number}
     * @memberof LedgerEntry
     */
    amount: number;
    /**
     * 
     * @type {string}
     * @memberof LedgerEntry
     */
    created_at: string;
}
/**
 * 
 * @export
 * @interface LedgerEntryPage
 */
export interface LedgerEntryPage {
    /**
     * 
     * @type {Array<LedgerEntry>}
     * @memberof LedgerEntryPage
     */
    data: Array<LedgerEntry>;
    /**
     * 
     * @type {boolean}
     * @memberof LedgerEntryPage
     */
    has_more: boolean;
    /**
     * Pass as `starting_after` to get the next page; null on the last page.
     * @type {string}
     * @memberof LedgerEntryPage
     */
    next_cursor: string | null;
}
/**
 * 
 * @export
 * @interface ListProviders200ResponseInner
 */
export interface ListProviders200ResponseInner {
    /**
     * 
     * @type {string}
     * @memberof ListProviders200ResponseInner
     */
    id: string;
    /**
     * Lower is preferred.
     * @type {number}
     * @memberof ListProviders200ResponseInner
     */
    priority: number;
    /**
     * False while the provider's circuit breaker is open.
     * @type {boolean}
     * @memberof ListProviders200ResponseInner
     */
    available: boolean;
    /**
     * 
     * @type {Array<ListProviders200ResponseInnerCapabilitiesInner>}
     * @memberof ListProviders200ResponseInner
     */
    capabilities: Array<ListProviders200ResponseInnerCapabilitiesInner>;
}
/**
 * 
 * @export
 * @interface ListProviders200ResponseInnerCapabilitiesInner
 */
export interface ListProviders200ResponseInnerCapabilitiesInner {
    /**
     * 
     * @type {string}
     * @memberof ListProviders200ResponseInnerCapabilitiesInner
     */
    operation: ListProviders200ResponseInnerCapabilitiesInnerOperationEnum;
    /**
     * 
     * @type {string}
     * @memberof ListProviders200ResponseInnerCapabilitiesInner
     */
    country: string;
    /**
     * 
     * @type {string}
     * @memberof ListProviders200ResponseInnerCapabilitiesInner
     */
    method: string;
    /**
     * 
     * @type {string}
     * @memberof ListProviders200ResponseInnerCapabilitiesInner
     */
    currency: string;
}


/**
 * @export
 */
export const ListProviders200ResponseInnerCapabilitiesInnerOperationEnum = {
    Collect: 'collect',
    Refund: 'refund',
    Payout: 'payout'
} as const;
export type ListProviders200ResponseInnerCapabilitiesInnerOperationEnum = typeof ListProviders200ResponseInnerCapabilitiesInnerOperationEnum[keyof typeof ListProviders200ResponseInnerCapabilitiesInnerOperationEnum];

/**
 * 
 * @export
 * @interface Payment
 */
export interface Payment {
    /**
     * 
     * @type {string}
     * @memberof Payment
     */
    id: string;
    /**
     * 
     * @type {string}
     * @memberof Payment
     */
    object: PaymentObjectEnum;
    /**
     * 
     * @type {PaymentStatus}
     * @memberof Payment
     */
    status: PaymentStatus;
    /**
     * 
     * @type {number}
     * @memberof Payment
     */
    amount: number;
    /**
     * 
     * @type {number}
     * @memberof Payment
     */
    amount_refunded: number;
    /**
     * 
     * @type {string}
     * @memberof Payment
     */
    currency: string;
    /**
     * 
     * @type {string}
     * @memberof Payment
     */
    country: string;
    /**
     * 
     * @type {string}
     * @memberof Payment
     */
    method: string;
    /**
     * 
     * @type {string}
     * @memberof Payment
     */
    reference?: string | null;
    /**
     * 
     * @type {string}
     * @memberof Payment
     */
    description?: string | null;
    /**
     * 
     * @type {PaymentCustomer}
     * @memberof Payment
     */
    customer?: PaymentCustomer;
    /**
     * 
     * @type {string}
     * @memberof Payment
     */
    provider?: string | null;
    /**
     * 
     * @type {string}
     * @memberof Payment
     */
    provider_reference?: string | null;
    /**
     * Redirect the customer here
     * @type {string}
     * @memberof Payment
     */
    checkout_url?: string | null;
    /**
     * Push/USSD instruction to show the customer
     * @type {string}
     * @memberof Payment
     */
    instructions?: string | null;
    /**
     * Why this provider — and any rejections before it.
     * @type {string}
     * @memberof Payment
     */
    routing_reason?: string | null;
    /**
     * 
     * @type {Failure}
     * @memberof Payment
     */
    failure?: Failure;
    /**
     * 
     * @type {string}
     * @memberof Payment
     */
    created_at: string;
    /**
     * 
     * @type {string}
     * @memberof Payment
     */
    updated_at: string;
}


/**
 * @export
 */
export const PaymentObjectEnum = {
    Payment: 'payment'
} as const;
export type PaymentObjectEnum = typeof PaymentObjectEnum[keyof typeof PaymentObjectEnum];

/**
 * 
 * @export
 * @interface PaymentCustomer
 */
export interface PaymentCustomer {
    /**
     * 
     * @type {string}
     * @memberof PaymentCustomer
     */
    phone?: string | null;
}
/**
 * 
 * @export
 * @interface PaymentPage
 */
export interface PaymentPage {
    /**
     * 
     * @type {Array<Payment>}
     * @memberof PaymentPage
     */
    data: Array<Payment>;
    /**
     * 
     * @type {boolean}
     * @memberof PaymentPage
     */
    has_more: boolean;
    /**
     * Pass as `starting_after` to get the next page; null on the last page.
     * @type {string}
     * @memberof PaymentPage
     */
    next_cursor: string | null;
}

/**
 * `pending` — with the provider, waiting for the customer (or for the provider's answer).
 * `succeeded` is final. `failed` and `expired` can still become `succeeded` if the provider
 * later confirms the money arrived.
 * 
 * @export
 */
export const PaymentStatus = {
    Created: 'created',
    Pending: 'pending',
    Succeeded: 'succeeded',
    Failed: 'failed',
    Expired: 'expired'
} as const;
export type PaymentStatus = typeof PaymentStatus[keyof typeof PaymentStatus];

/**
 * 
 * @export
 * @interface Payout
 */
export interface Payout {
    /**
     * 
     * @type {string}
     * @memberof Payout
     */
    id: string;
    /**
     * 
     * @type {string}
     * @memberof Payout
     */
    object: PayoutObjectEnum;
    /**
     * 
     * @type {PayoutStatus}
     * @memberof Payout
     */
    status: PayoutStatus;
    /**
     * 
     * @type {number}
     * @memberof Payout
     */
    amount: number;
    /**
     * 
     * @type {string}
     * @memberof Payout
     */
    currency: string;
    /**
     * 
     * @type {string}
     * @memberof Payout
     */
    country: string;
    /**
     * 
     * @type {string}
     * @memberof Payout
     */
    method: string;
    /**
     * 
     * @type {string}
     * @memberof Payout
     */
    reference?: string | null;
    /**
     * 
     * @type {PayoutRecipient}
     * @memberof Payout
     */
    recipient: PayoutRecipient;
    /**
     * 
     * @type {string}
     * @memberof Payout
     */
    provider: string;
    /**
     * 
     * @type {string}
     * @memberof Payout
     */
    provider_reference?: string | null;
    /**
     * 
     * @type {string}
     * @memberof Payout
     */
    routing_reason?: string | null;
    /**
     * The outcome stayed unknown too long; a human should check with the provider.
     * @type {boolean}
     * @memberof Payout
     */
    needs_review: boolean;
    /**
     * 
     * @type {Failure}
     * @memberof Payout
     */
    failure?: Failure;
    /**
     * 
     * @type {string}
     * @memberof Payout
     */
    created_at: string;
    /**
     * 
     * @type {string}
     * @memberof Payout
     */
    updated_at: string;
}


/**
 * @export
 */
export const PayoutObjectEnum = {
    Payout: 'payout'
} as const;
export type PayoutObjectEnum = typeof PayoutObjectEnum[keyof typeof PayoutObjectEnum];

/**
 * 
 * @export
 * @interface PayoutPage
 */
export interface PayoutPage {
    /**
     * 
     * @type {Array<Payout>}
     * @memberof PayoutPage
     */
    data: Array<Payout>;
    /**
     * 
     * @type {boolean}
     * @memberof PayoutPage
     */
    has_more: boolean;
    /**
     * Pass as `starting_after` to get the next page; null on the last page.
     * @type {string}
     * @memberof PayoutPage
     */
    next_cursor: string | null;
}
/**
 * 
 * @export
 * @interface PayoutRecipient
 */
export interface PayoutRecipient {
    /**
     * Masked. Null when the payout went to a PI-SPI alias.
     * @type {string}
     * @memberof PayoutRecipient
     */
    phone: string | null;
}

/**
 * 
 * @export
 */
export const PayoutStatus = {
    Created: 'created',
    Processing: 'processing',
    Unknown: 'unknown',
    Paid: 'paid',
    Failed: 'failed'
} as const;
export type PayoutStatus = typeof PayoutStatus[keyof typeof PayoutStatus];

/**
 * 
 * @export
 * @interface Problem
 */
export interface Problem {
    /**
     * 
     * @type {string}
     * @memberof Problem
     */
    type: string;
    /**
     * 
     * @type {string}
     * @memberof Problem
     */
    title: string;
    /**
     * 
     * @type {number}
     * @memberof Problem
     */
    status: number;
    /**
     * 
     * @type {string}
     * @memberof Problem
     */
    detail?: string;
    /**
     * 
     * @type {string}
     * @memberof Problem
     */
    instance?: string;
    /**
     * Stable, machine-readable error code.
     * @type {string}
     * @memberof Problem
     */
    code: string;
}
/**
 * 
 * @export
 * @interface ReceiveProviderCallback200Response
 */
export interface ReceiveProviderCallback200Response {
    /**
     * 
     * @type {boolean}
     * @memberof ReceiveProviderCallback200Response
     */
    received: boolean;
}
/**
 * 
 * @export
 * @interface Refund
 */
export interface Refund {
    /**
     * 
     * @type {string}
     * @memberof Refund
     */
    id: string;
    /**
     * 
     * @type {string}
     * @memberof Refund
     */
    object: RefundObjectEnum;
    /**
     * 
     * @type {string}
     * @memberof Refund
     */
    payment_id: string;
    /**
     * 
     * @type {RefundStatus}
     * @memberof Refund
     */
    status: RefundStatus;
    /**
     * 
     * @type {number}
     * @memberof Refund
     */
    amount: number;
    /**
     * 
     * @type {string}
     * @memberof Refund
     */
    currency: string;
    /**
     * 
     * @type {string}
     * @memberof Refund
     */
    reason?: string | null;
    /**
     * 
     * @type {string}
     * @memberof Refund
     */
    provider: string;
    /**
     * 
     * @type {string}
     * @memberof Refund
     */
    provider_reference?: string | null;
    /**
     * 
     * @type {Failure}
     * @memberof Refund
     */
    failure?: Failure;
    /**
     * 
     * @type {string}
     * @memberof Refund
     */
    created_at: string;
    /**
     * 
     * @type {string}
     * @memberof Refund
     */
    updated_at: string;
}


/**
 * @export
 */
export const RefundObjectEnum = {
    Refund: 'refund'
} as const;
export type RefundObjectEnum = typeof RefundObjectEnum[keyof typeof RefundObjectEnum];

/**
 * 
 * @export
 * @interface RefundPage
 */
export interface RefundPage {
    /**
     * 
     * @type {Array<Refund>}
     * @memberof RefundPage
     */
    data: Array<Refund>;
    /**
     * 
     * @type {boolean}
     * @memberof RefundPage
     */
    has_more: boolean;
    /**
     * Pass as `starting_after` to get the next page; null on the last page.
     * @type {string}
     * @memberof RefundPage
     */
    next_cursor: string | null;
}

/**
 * 
 * @export
 */
export const RefundStatus = {
    Created: 'created',
    Pending: 'pending',
    Unknown: 'unknown',
    Refunded: 'refunded',
    Failed: 'failed'
} as const;
export type RefundStatus = typeof RefundStatus[keyof typeof RefundStatus];

/**
 * 
 * @export
 * @interface StatusEvent
 */
export interface StatusEvent {
    /**
     * 
     * @type {string}
     * @memberof StatusEvent
     */
    from_status?: string | null;
    /**
     * 
     * @type {string}
     * @memberof StatusEvent
     */
    to_status: string;
    /**
     * `ignore` — recorded but not applied (a duplicate, or late news such as a failure after success).
     * @type {string}
     * @memberof StatusEvent
     */
    decision: StatusEventDecisionEnum;
    /**
     * 
     * @type {string}
     * @memberof StatusEvent
     */
    cause: StatusEventCauseEnum;
    /**
     * The provider's own status value.
     * @type {string}
     * @memberof StatusEvent
     */
    raw_status?: string | null;
    /**
     * 
     * @type {string}
     * @memberof StatusEvent
     */
    detail?: string | null;
    /**
     * 
     * @type {string}
     * @memberof StatusEvent
     */
    created_at: string;
}


/**
 * @export
 */
export const StatusEventDecisionEnum = {
    Apply: 'apply',
    Ignore: 'ignore'
} as const;
export type StatusEventDecisionEnum = typeof StatusEventDecisionEnum[keyof typeof StatusEventDecisionEnum];

/**
 * @export
 */
export const StatusEventCauseEnum = {
    Api: 'api',
    ProviderCall: 'provider_call',
    Webhook: 'webhook',
    Sweep: 'sweep',
    Admin: 'admin'
} as const;
export type StatusEventCauseEnum = typeof StatusEventCauseEnum[keyof typeof StatusEventCauseEnum];

