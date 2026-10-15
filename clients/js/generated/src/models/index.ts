/* tslint:disable */
/* eslint-disable */
/**
 * 
 * @export
 * @interface AdminGetBalances200Response
 */
export interface AdminGetBalances200Response {
    /**
     * 
     * @type {string}
     * @memberof AdminGetBalances200Response
     */
    object: AdminGetBalances200ResponseObjectEnum;
    /**
     * 
     * @type {string}
     * @memberof AdminGetBalances200Response
     */
    note: string;
    /**
     * 
     * @type {Array<AdminGetBalances200ResponseDataInner>}
     * @memberof AdminGetBalances200Response
     */
    data: Array<AdminGetBalances200ResponseDataInner>;
}


/**
 * @export
 */
export const AdminGetBalances200ResponseObjectEnum = {
    Balances: 'balances'
} as const;
export type AdminGetBalances200ResponseObjectEnum = typeof AdminGetBalances200ResponseObjectEnum[keyof typeof AdminGetBalances200ResponseObjectEnum];

/**
 * 
 * @export
 * @interface AdminGetBalances200ResponseDataInner
 */
export interface AdminGetBalances200ResponseDataInner {
    /**
     * 
     * @type {string}
     * @memberof AdminGetBalances200ResponseDataInner
     */
    account: string;
    /**
     * 
     * @type {string}
     * @memberof AdminGetBalances200ResponseDataInner
     */
    provider: string;
    /**
     * 
     * @type {string}
     * @memberof AdminGetBalances200ResponseDataInner
     */
    currency: string;
    /**
     * 
     * @type {number}
     * @memberof AdminGetBalances200ResponseDataInner
     */
    amount: number;
}
/**
 * 
 * @export
 * @interface AdminListApplications200Response
 */
export interface AdminListApplications200Response {
    /**
     * 
     * @type {Array<AdminListApplications200ResponseDataInner>}
     * @memberof AdminListApplications200Response
     */
    data: Array<AdminListApplications200ResponseDataInner>;
}
/**
 * 
 * @export
 * @interface AdminListApplications200ResponseDataInner
 */
export interface AdminListApplications200ResponseDataInner {
    /**
     * 
     * @type {string}
     * @memberof AdminListApplications200ResponseDataInner
     */
    id: string;
    /**
     * 
     * @type {string}
     * @memberof AdminListApplications200ResponseDataInner
     */
    object: AdminListApplications200ResponseDataInnerObjectEnum;
    /**
     * 
     * @type {string}
     * @memberof AdminListApplications200ResponseDataInner
     */
    name: string;
    /**
     * 
     * @type {string}
     * @memberof AdminListApplications200ResponseDataInner
     */
    created_at: string;
}


/**
 * @export
 */
export const AdminListApplications200ResponseDataInnerObjectEnum = {
    Application: 'application'
} as const;
export type AdminListApplications200ResponseDataInnerObjectEnum = typeof AdminListApplications200ResponseDataInnerObjectEnum[keyof typeof AdminListApplications200ResponseDataInnerObjectEnum];

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
 * @interface AmountOff
 */
export interface AmountOff {
    /**
     * 
     * @type {string}
     * @memberof AmountOff
     */
    currency: string;
    /**
     * Minor units.
     * @type {number}
     * @memberof AmountOff
     */
    amount: number;
}
/**
 * 
 * @export
 * @interface ApplySubscriptionDiscountRequest
 */
export interface ApplySubscriptionDiscountRequest {
    /**
     * 
     * @type {string}
     * @memberof ApplySubscriptionDiscountRequest
     */
    coupon: string;
}
/**
 * 
 * @export
 * @interface CancelSubscriptionRequest
 */
export interface CancelSubscriptionRequest {
    /**
     * 
     * @type {boolean}
     * @memberof CancelSubscriptionRequest
     */
    at_period_end?: boolean;
}
/**
 * 
 * @export
 * @interface ChangeSubscriptionRequest
 */
export interface ChangeSubscriptionRequest {
    /**
     * 
     * @type {string}
     * @memberof ChangeSubscriptionRequest
     */
    price: string;
    /**
     * Default: `now` for an upgrade, `period_end` otherwise.
     * @type {string}
     * @memberof ChangeSubscriptionRequest
     */
    when?: ChangeSubscriptionRequestWhenEnum;
    /**
     * 
     * @type {string}
     * @memberof ChangeSubscriptionRequest
     */
    return_url?: string;
}


/**
 * @export
 */
export const ChangeSubscriptionRequestWhenEnum = {
    Now: 'now',
    PeriodEnd: 'period_end'
} as const;
export type ChangeSubscriptionRequestWhenEnum = typeof ChangeSubscriptionRequestWhenEnum[keyof typeof ChangeSubscriptionRequestWhenEnum];

/**
 * 
 * @export
 * @interface CheckoutAttemptRequest
 */
export interface CheckoutAttemptRequest {
    /**
     * 
     * @type {string}
     * @memberof CheckoutAttemptRequest
     */
    method: string;
    /**
     * Optional; validated for the payment country.
     * @type {string}
     * @memberof CheckoutAttemptRequest
     */
    phone?: string;
    /**
     * 
     * @type {string}
     * @memberof CheckoutAttemptRequest
     */
    pi_alias?: string;
}
/**
 * What the hosted checkout page shows. Holds no secret and no other object.
 * @export
 * @interface CheckoutView
 */
export interface CheckoutView {
    /**
     * 
     * @type {string}
     * @memberof CheckoutView
     */
    id: string;
    /**
     * 
     * @type {PaymentStatus}
     * @memberof CheckoutView
     */
    status: PaymentStatus;
    /**
     * Minor units.
     * @type {number}
     * @memberof CheckoutView
     */
    amount: number;
    /**
     * 
     * @type {string}
     * @memberof CheckoutView
     */
    currency: string;
    /**
     * 
     * @type {string}
     * @memberof CheckoutView
     */
    country: string;
    /**
     * 
     * @type {string}
     * @memberof CheckoutView
     */
    description: string | null;
    /**
     * The method chosen last.
     * @type {string}
     * @memberof CheckoutView
     */
    method: string | null;
    /**
     * 
     * @type {Array<CheckoutViewMethodsInner>}
     * @memberof CheckoutView
     */
    methods: Array<CheckoutViewMethodsInner>;
    /**
     * True while the customer may start an attempt.
     * @type {boolean}
     * @memberof CheckoutView
     */
    can_choose: boolean;
    /**
     * 
     * @type {CheckoutViewNextAction}
     * @memberof CheckoutView
     */
    next_action: CheckoutViewNextAction;
    /**
     * Masked.
     * @type {string}
     * @memberof CheckoutView
     */
    customer_phone: string | null;
    /**
     * 
     * @type {boolean}
     * @memberof CheckoutView
     */
    has_pi_alias: boolean;
    /**
     * The application's page to go back to.
     * @type {string}
     * @memberof CheckoutView
     */
    return_url: string | null;
    /**
     * 
     * @type {string}
     * @memberof CheckoutView
     */
    expires_at: string | null;
    /**
     * 
     * @type {CheckoutViewLastError}
     * @memberof CheckoutView
     */
    last_error: CheckoutViewLastError;
    /**
     * 
     * @type {CheckoutViewBilling}
     * @memberof CheckoutView
     */
    billing?: CheckoutViewBilling;
}


/**
 * Set when the payment pays a subscription invoice.
 * @export
 * @interface CheckoutViewBilling
 */
export interface CheckoutViewBilling {
    /**
     * 
     * @type {string}
     * @memberof CheckoutViewBilling
     */
    invoice_number: string;
    /**
     * first, renewal or upgrade (open set).
     * @type {string}
     * @memberof CheckoutViewBilling
     */
    kind: string;
    /**
     * The plan's name.
     * @type {string}
     * @memberof CheckoutViewBilling
     */
    plan: string;
    /**
     * 
     * @type {Interval}
     * @memberof CheckoutViewBilling
     */
    interval: Interval;
    /**
     * 
     * @type {number}
     * @memberof CheckoutViewBilling
     */
    interval_count: number;
    /**
     * Relative; once this payment has paid the invoice.
     * @type {string}
     * @memberof CheckoutViewBilling
     */
    receipt_url: string | null;
    /**
     * Before discount; the payment amount is what is due.
     * @type {number}
     * @memberof CheckoutViewBilling
     */
    subtotal?: number;
    /**
     * 
     * @type {string}
     * @memberof CheckoutViewBilling
     */
    coupon_code?: string | null;
}


/**
 * Why the last round was refused by every provider (only right after it).
 * @export
 * @interface CheckoutViewLastError
 */
export interface CheckoutViewLastError {
    /**
     * 
     * @type {string}
     * @memberof CheckoutViewLastError
     */
    code: string;
}
/**
 * 
 * @export
 * @interface CheckoutViewMethodsInner
 */
export interface CheckoutViewMethodsInner {
    /**
     * 
     * @type {string}
     * @memberof CheckoutViewMethodsInner
     */
    method: string;
    /**
     * False while every provider for it is unavailable.
     * @type {boolean}
     * @memberof CheckoutViewMethodsInner
     */
    available: boolean;
    /**
     * `pi_alias`: ask the customer for their PI-SPI alias (unless `has_pi_alias`).
     * @type {string}
     * @memberof CheckoutViewMethodsInner
     */
    needs: CheckoutViewMethodsInnerNeedsEnum;
}


/**
 * @export
 */
export const CheckoutViewMethodsInnerNeedsEnum = {
    None: 'none',
    PiAlias: 'pi_alias'
} as const;
export type CheckoutViewMethodsInnerNeedsEnum = typeof CheckoutViewMethodsInnerNeedsEnum[keyof typeof CheckoutViewMethodsInnerNeedsEnum];

/**
 * 
 * @export
 * @interface CheckoutViewNextAction
 */
export interface CheckoutViewNextAction {
    /**
     * 
     * @type {string}
     * @memberof CheckoutViewNextAction
     */
    type: CheckoutViewNextActionTypeEnum;
    /**
     * The provider's checkout page.
     * @type {string}
     * @memberof CheckoutViewNextAction
     */
    url: string | null;
    /**
     * Push/USSD instructions from the provider.
     * @type {string}
     * @memberof CheckoutViewNextAction
     */
    instructions: string | null;
}


/**
 * @export
 */
export const CheckoutViewNextActionTypeEnum = {
    Redirect: 'redirect',
    Instructions: 'instructions'
} as const;
export type CheckoutViewNextActionTypeEnum = typeof CheckoutViewNextActionTypeEnum[keyof typeof CheckoutViewNextActionTypeEnum];

/**
 * Exactly one of `periods`, `until` and `open`.
 * @export
 * @interface CompRequest
 */
export interface CompRequest {
    [key: string]: any | any;
    /**
     * The price granted (any price of the application).
     * @type {string}
     * @memberof CompRequest
     */
    price: string;
    /**
     * 
     * @type {number}
     * @memberof CompRequest
     */
    periods?: number;
    /**
     * Grant time up to this instant instead of whole periods — e.g. what a customer already
     * paid for before migrating to Yoon. After the paid period, if any; later renewals fall on
     * its day of the month.
     * 
     * @type {string}
     * @memberof CompRequest
     */
    until?: string;
    /**
     * true: no end date (current_period_end null) until canceled.
     * @type {boolean}
     * @memberof CompRequest
     */
    open?: boolean;
    /**
     * 
     * @type {string}
     * @memberof CompRequest
     */
    note: string;
}
/**
 * 
 * @export
 * @interface Coupon
 */
export interface Coupon {
    /**
     * 
     * @type {string}
     * @memberof Coupon
     */
    id: string;
    /**
     * 
     * @type {string}
     * @memberof Coupon
     */
    object: CouponObjectEnum;
    /**
     * 
     * @type {string}
     * @memberof Coupon
     */
    code: string;
    /**
     * 
     * @type {number}
     * @memberof Coupon
     */
    percent_off: number | null;
    /**
     * 
     * @type {AmountOff}
     * @memberof Coupon
     */
    amount_off: AmountOff | null;
    /**
     * `once`, `repeating` or `forever` (open set).
     * @type {string}
     * @memberof Coupon
     */
    duration: string;
    /**
     * 
     * @type {number}
     * @memberof Coupon
     */
    duration_in_periods: number | null;
    /**
     * 
     * @type {number}
     * @memberof Coupon
     */
    max_redemptions: number | null;
    /**
     * 
     * @type {number}
     * @memberof Coupon
     */
    times_redeemed: number;
    /**
     * 
     * @type {string}
     * @memberof Coupon
     */
    redeem_by: string | null;
    /**
     * 
     * @type {Array<string>}
     * @memberof Coupon
     */
    plans: Array<string> | null;
    /**
     * 
     * @type {boolean}
     * @memberof Coupon
     */
    active: boolean;
    /**
     * Your own key/value pairs. At most 20 keys; keys ≤ 40 characters, values ≤ 500.
     * @type {{ [key: string]: string; }}
     * @memberof Coupon
     */
    metadata: { [key: string]: string; };
    /**
     * 
     * @type {string}
     * @memberof Coupon
     */
    created_at: string;
}


/**
 * @export
 */
export const CouponObjectEnum = {
    Coupon: 'coupon'
} as const;
export type CouponObjectEnum = typeof CouponObjectEnum[keyof typeof CouponObjectEnum];

/**
 * 
 * @export
 * @interface CouponPage
 */
export interface CouponPage {
    /**
     * 
     * @type {Array<Coupon>}
     * @memberof CouponPage
     */
    data: Array<Coupon>;
    /**
     * 
     * @type {boolean}
     * @memberof CouponPage
     */
    has_more: boolean;
    /**
     * Pass as `starting_after` to get the next page; null on the last page.
     * @type {string}
     * @memberof CouponPage
     */
    next_cursor: string | null;
}
/**
 * Exactly one of `percent_off` and `amount_off`.
 * @export
 * @interface CreateCouponRequest
 */
export interface CreateCouponRequest {
    /**
     * What customers type. Case-insensitive.
     * @type {string}
     * @memberof CreateCouponRequest
     */
    code: string;
    /**
     * 
     * @type {number}
     * @memberof CreateCouponRequest
     */
    percent_off?: number;
    /**
     * 
     * @type {AmountOff}
     * @memberof CreateCouponRequest
     */
    amount_off?: AmountOff;
    /**
     * 
     * @type {string}
     * @memberof CreateCouponRequest
     */
    duration: CreateCouponRequestDurationEnum;
    /**
     * Required for, and only for, `repeating`.
     * @type {number}
     * @memberof CreateCouponRequest
     */
    duration_in_periods?: number;
    /**
     * 
     * @type {number}
     * @memberof CreateCouponRequest
     */
    max_redemptions?: number;
    /**
     * 
     * @type {string}
     * @memberof CreateCouponRequest
     */
    redeem_by?: string;
    /**
     * Plan ids it is limited to; omit for every plan.
     * @type {Array<string>}
     * @memberof CreateCouponRequest
     */
    plans?: Array<string>;
    /**
     * Your own key/value pairs. At most 20 keys; keys ≤ 40 characters, values ≤ 500.
     * @type {{ [key: string]: string; }}
     * @memberof CreateCouponRequest
     */
    metadata?: { [key: string]: string; };
}


/**
 * @export
 */
export const CreateCouponRequestDurationEnum = {
    Once: 'once',
    Repeating: 'repeating',
    Forever: 'forever'
} as const;
export type CreateCouponRequestDurationEnum = typeof CreateCouponRequestDurationEnum[keyof typeof CreateCouponRequestDurationEnum];

/**
 * 
 * @export
 * @interface CreateCustomerRequest
 */
export interface CreateCustomerRequest {
    /**
     * 
     * @type {string}
     * @memberof CreateCustomerRequest
     */
    external_id: string;
    /**
     * 
     * @type {string}
     * @memberof CreateCustomerRequest
     */
    email?: string;
    /**
     * Local or international; validated for `country`.
     * @type {string}
     * @memberof CreateCustomerRequest
     */
    phone?: string;
    /**
     * 
     * @type {string}
     * @memberof CreateCustomerRequest
     */
    pi_alias?: string;
    /**
     * 
     * @type {string}
     * @memberof CreateCustomerRequest
     */
    country: string;
    /**
     * 
     * @type {string}
     * @memberof CreateCustomerRequest
     */
    name?: string;
    /**
     * Your own key/value pairs. At most 20 keys; keys ≤ 40 characters, values ≤ 500.
     * @type {{ [key: string]: string; }}
     * @memberof CreateCustomerRequest
     */
    metadata?: { [key: string]: string; };
}
/**
 * 
 * @export
 * @interface CreatePaymentLinkRequest
 */
export interface CreatePaymentLinkRequest {
    /**
     * Fixed amount in minor units; omit to let the customer type it.
     * @type {number}
     * @memberof CreatePaymentLinkRequest
     */
    amount?: number;
    /**
     * Only without `amount`.
     * @type {number}
     * @memberof CreatePaymentLinkRequest
     */
    min_amount?: number;
    /**
     * Only without `amount`.
     * @type {number}
     * @memberof CreatePaymentLinkRequest
     */
    max_amount?: number;
    /**
     * 
     * @type {string}
     * @memberof CreatePaymentLinkRequest
     */
    currency: string;
    /**
     * 
     * @type {string}
     * @memberof CreatePaymentLinkRequest
     */
    country: string;
    /**
     * 
     * @type {string}
     * @memberof CreatePaymentLinkRequest
     */
    description: string;
    /**
     * Limit to one method (e.g. wave); default any.
     * @type {string}
     * @memberof CreatePaymentLinkRequest
     */
    method?: string;
    /**
     * 1 = single use; omit for a reusable link.
     * @type {number}
     * @memberof CreatePaymentLinkRequest
     */
    max_payments?: number;
    /**
     * 
     * @type {string}
     * @memberof CreatePaymentLinkRequest
     */
    expires_at?: string;
    /**
     * 
     * @type {string}
     * @memberof CreatePaymentLinkRequest
     */
    return_url?: string;
    /**
     * Your own key/value pairs. At most 20 keys; keys ≤ 40 characters, values ≤ 500.
     * @type {{ [key: string]: string; }}
     * @memberof CreatePaymentLinkRequest
     */
    metadata?: { [key: string]: string; };
}
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
     * e.g. wave, orange_money, free_money, card, pispi. Required unless `checkout` is `hosted`;
     * with `hosted` it limits the checkout page to this one method.
     * 
     * @type {string}
     * @memberof CreatePaymentRequest
     */
    method?: string;
    /**
     * `direct` (default): Yoon calls a provider now. `hosted`: Yoon calls no provider yet and
     * returns `checkout_url`, a Yoon page where the customer picks the method (ADR-0024).
     * 
     * @type {string}
     * @memberof CreatePaymentRequest
     */
    checkout?: CreatePaymentRequestCheckoutEnum;
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
 * @export
 */
export const CreatePaymentRequestCheckoutEnum = {
    Direct: 'direct',
    Hosted: 'hosted'
} as const;
export type CreatePaymentRequestCheckoutEnum = typeof CreatePaymentRequestCheckoutEnum[keyof typeof CreatePaymentRequestCheckoutEnum];

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
 * @interface CreatePlanRequest
 */
export interface CreatePlanRequest {
    /**
     * Unique per application.
     * @type {string}
     * @memberof CreatePlanRequest
     */
    slug: string;
    /**
     * 
     * @type {string}
     * @memberof CreatePlanRequest
     */
    name: string;
    /**
     * 
     * @type {{ [key: string]: any; }}
     * @memberof CreatePlanRequest
     */
    features?: { [key: string]: any; };
    /**
     * Your own key/value pairs. At most 20 keys; keys ≤ 40 characters, values ≤ 500.
     * @type {{ [key: string]: string; }}
     * @memberof CreatePlanRequest
     */
    metadata?: { [key: string]: string; };
    /**
     * 
     * @type {Array<PriceInput>}
     * @memberof CreatePlanRequest
     */
    prices?: Array<PriceInput>;
    /**
     * Default free trial for new subscriptions.
     * @type {number}
     * @memberof CreatePlanRequest
     */
    trial_days?: number;
}
/**
 * 
 * @export
 * @interface CreatePortalSessionRequest
 */
export interface CreatePortalSessionRequest {
    /**
     * The "Back" link of the portal.
     * @type {string}
     * @memberof CreatePortalSessionRequest
     */
    return_url?: string;
    /**
     * 
     * @type {CreatePortalSessionRequestAllow}
     * @memberof CreatePortalSessionRequest
     */
    allow?: CreatePortalSessionRequestAllow;
}
/**
 * What the customer may do beyond viewing and paying. Omit for view and pay only.
 * @export
 * @interface CreatePortalSessionRequestAllow
 */
export interface CreatePortalSessionRequestAllow {
    /**
     * Cancel at period end
     * @type {boolean}
     * @memberof CreatePortalSessionRequestAllow
     */
    cancel?: boolean;
    /**
     * Prices the customer may change to.
     * @type {Array<string>}
     * @memberof CreatePortalSessionRequestAllow
     */
    change_prices?: Array<string>;
    /**
     * Redeem a coupon code.
     * @type {boolean}
     * @memberof CreatePortalSessionRequestAllow
     */
    coupons?: boolean;
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
 * 
 * @export
 * @interface CreateSubscriptionRequest
 */
export interface CreateSubscriptionRequest {
    /**
     * 
     * @type {string}
     * @memberof CreateSubscriptionRequest
     */
    customer: string;
    /**
     * 
     * @type {string}
     * @memberof CreateSubscriptionRequest
     */
    price: string;
    /**
     * Default from the application's billing settings.
     * @type {number}
     * @memberof CreateSubscriptionRequest
     */
    grace_period_days?: number;
    /**
     * Your own key/value pairs. At most 20 keys; keys ≤ 40 characters, values ≤ 500.
     * @type {{ [key: string]: string; }}
     * @memberof CreateSubscriptionRequest
     */
    metadata?: { [key: string]: string; };
    /**
     * Required unless the subscription starts with a trial.
     * @type {string}
     * @memberof CreateSubscriptionRequest
     */
    return_url?: string;
    /**
     * A coupon code (case-insensitive) for its first invoices; `422 coupon_not_applicable` with a `reason` if it cannot be used.
     * @type {string}
     * @memberof CreateSubscriptionRequest
     */
    coupon?: string;
    /**
     * Free trial (ADR-0028); default: the plan's `trial_days`, 0 for none. A customer gets one
     * trial per plan: asking for one again is `409 trial_already_used`; without `trial_days`
     * the plan's default is simply not applied.
     * 
     * @type {number}
     * @memberof CreateSubscriptionRequest
     */
    trial_days?: number;
}
/**
 * 
 * @export
 * @interface Customer
 */
export interface Customer {
    /**
     * 
     * @type {string}
     * @memberof Customer
     */
    id: string;
    /**
     * 
     * @type {string}
     * @memberof Customer
     */
    object: CustomerObjectEnum;
    /**
     * Your own id for this user.
     * @type {string}
     * @memberof Customer
     */
    external_id: string;
    /**
     * 
     * @type {string}
     * @memberof Customer
     */
    email?: string | null;
    /**
     * Masked.
     * @type {string}
     * @memberof Customer
     */
    phone?: string | null;
    /**
     * 
     * @type {string}
     * @memberof Customer
     */
    pi_alias?: string | null;
    /**
     * 
     * @type {string}
     * @memberof Customer
     */
    country: string;
    /**
     * 
     * @type {string}
     * @memberof Customer
     */
    name?: string | null;
    /**
     * Your own key/value pairs. At most 20 keys; keys ≤ 40 characters, values ≤ 500.
     * @type {{ [key: string]: string; }}
     * @memberof Customer
     */
    metadata: { [key: string]: string; };
    /**
     * 
     * @type {string}
     * @memberof Customer
     */
    created_at: string;
    /**
     * 
     * @type {string}
     * @memberof Customer
     */
    updated_at: string;
}


/**
 * @export
 */
export const CustomerObjectEnum = {
    Customer: 'customer'
} as const;
export type CustomerObjectEnum = typeof CustomerObjectEnum[keyof typeof CustomerObjectEnum];

/**
 * 
 * @export
 * @interface CustomerPage
 */
export interface CustomerPage {
    /**
     * 
     * @type {Array<Customer>}
     * @memberof CustomerPage
     */
    data: Array<Customer>;
    /**
     * 
     * @type {boolean}
     * @memberof CustomerPage
     */
    has_more: boolean;
    /**
     * Pass as `starting_after` to get the next page; null on the last page.
     * @type {string}
     * @memberof CustomerPage
     */
    next_cursor: string | null;
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
 * @interface Discount
 */
export interface Discount {
    /**
     * 
     * @type {string}
     * @memberof Discount
     */
    coupon: string;
    /**
     * 
     * @type {string}
     * @memberof Discount
     */
    code: string;
    /**
     * 
     * @type {number}
     * @memberof Discount
     */
    percent_off: number | null;
    /**
     * 
     * @type {number}
     * @memberof Discount
     */
    amount_off: number | null;
    /**
     * On a subscription: invoices it still discounts (null = forever). Null on an invoice.
     * @type {number}
     * @memberof Discount
     */
    periods_remaining: number | null;
}
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
    Payout: 'payout',
    Subscription: 'subscription',
    Invoice: 'invoice'
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
 * The payment, refund, payout, subscription or invoice as `GET` would return it at the time of the event.
 * @export
 */
export type EventPayloadDataObject = { object: 'invoice' } & Invoice | { object: 'payment' } & Payment | { object: 'payout' } & Payout | { object: 'refund' } & Refund | { object: 'subscription' } & Subscription;

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
    PayoutNeedsReview: 'payout.needs_review',
    SubscriptionCreated: 'subscription.created',
    SubscriptionActivated: 'subscription.activated',
    SubscriptionRenewed: 'subscription.renewed',
    SubscriptionPastDue: 'subscription.past_due',
    SubscriptionExpired: 'subscription.expired',
    SubscriptionCanceled: 'subscription.canceled',
    SubscriptionChanged: 'subscription.changed',
    SubscriptionRenewalUpcoming: 'subscription.renewal_upcoming',
    SubscriptionTrialWillEnd: 'subscription.trial_will_end',
    InvoiceCreated: 'invoice.created',
    InvoicePaid: 'invoice.paid',
    InvoiceVoided: 'invoice.voided'
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
 * Combined with `interval_count`; a quarter is `month` × 3.
 * @export
 */
export const Interval = {
    Day: 'day',
    Week: 'week',
    Month: 'month',
    Year: 'year'
} as const;
export type Interval = typeof Interval[keyof typeof Interval];

/**
 * A billing record — not a tax invoice (ADR-0027).
 * @export
 * @interface Invoice
 */
export interface Invoice {
    /**
     * 
     * @type {string}
     * @memberof Invoice
     */
    id: string;
    /**
     * 
     * @type {string}
     * @memberof Invoice
     */
    object: InvoiceObjectEnum;
    /**
     * Gap-free per application.
     * @type {string}
     * @memberof Invoice
     */
    number: string;
    /**
     * 
     * @type {string}
     * @memberof Invoice
     */
    customer: string;
    /**
     * 
     * @type {string}
     * @memberof Invoice
     */
    subscription: string;
    /**
     * 
     * @type {string}
     * @memberof Invoice
     */
    kind: InvoiceKindEnum;
    /**
     * 
     * @type {InvoiceStatus}
     * @memberof Invoice
     */
    status: InvoiceStatus;
    /**
     * `subtotal − discount_amount`. 0 with a 100% coupon: paid at creation, without a payment.
     * @type {number}
     * @memberof Invoice
     */
    amount_due: number;
    /**
     * The price before discount.
     * @type {number}
     * @memberof Invoice
     */
    subtotal?: number;
    /**
     * 
     * @type {number}
     * @memberof Invoice
     */
    discount_amount?: number;
    /**
     * 
     * @type {Discount}
     * @memberof Invoice
     */
    discount?: Discount | null;
    /**
     * 
     * @type {string}
     * @memberof Invoice
     */
    currency: string;
    /**
     * 
     * @type {Array<InvoiceLine>}
     * @memberof Invoice
     */
    lines: Array<InvoiceLine>;
    /**
     * Set when paid.
     * @type {string}
     * @memberof Invoice
     */
    period_start?: string | null;
    /**
     * Set when paid.
     * @type {string}
     * @memberof Invoice
     */
    period_end?: string | null;
    /**
     * The hosted payment that pays it.
     * @type {string}
     * @memberof Invoice
     */
    payment?: string | null;
    /**
     * Where the customer pays
     * @type {string}
     * @memberof Invoice
     */
    checkout_url?: string | null;
    /**
     * The public payment receipt (token in the URL), once paid. A receipt, not a tax invoice.
     * @type {string}
     * @memberof Invoice
     */
    receipt_url?: string | null;
    /**
     * 
     * @type {string}
     * @memberof Invoice
     */
    paid_at?: string | null;
    /**
     * 
     * @type {string}
     * @memberof Invoice
     */
    voided_at?: string | null;
    /**
     * 
     * @type {string}
     * @memberof Invoice
     */
    created_at: string;
}


/**
 * @export
 */
export const InvoiceObjectEnum = {
    Invoice: 'invoice'
} as const;
export type InvoiceObjectEnum = typeof InvoiceObjectEnum[keyof typeof InvoiceObjectEnum];

/**
 * @export
 */
export const InvoiceKindEnum = {
    First: 'first',
    Renewal: 'renewal',
    Upgrade: 'upgrade'
} as const;
export type InvoiceKindEnum = typeof InvoiceKindEnum[keyof typeof InvoiceKindEnum];

/**
 * 
 * @export
 * @interface InvoiceLine
 */
export interface InvoiceLine {
    /**
     * 
     * @type {string}
     * @memberof InvoiceLine
     */
    description: string;
    /**
     * 
     * @type {string}
     * @memberof InvoiceLine
     */
    price: string;
    /**
     * 
     * @type {number}
     * @memberof InvoiceLine
     */
    amount: number;
}
/**
 * 
 * @export
 * @interface InvoicePage
 */
export interface InvoicePage {
    /**
     * 
     * @type {Array<Invoice>}
     * @memberof InvoicePage
     */
    data: Array<Invoice>;
    /**
     * 
     * @type {boolean}
     * @memberof InvoicePage
     */
    has_more: boolean;
    /**
     * Pass as `starting_after` to get the next page; null on the last page.
     * @type {string}
     * @memberof InvoicePage
     */
    next_cursor: string | null;
}

/**
 * 
 * @export
 */
export const InvoiceStatus = {
    Open: 'open',
    Paid: 'paid',
    Void: 'void',
    Uncollectible: 'uncollectible'
} as const;
export type InvoiceStatus = typeof InvoiceStatus[keyof typeof InvoiceStatus];

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
 * @interface PayPaymentLink201Response
 */
export interface PayPaymentLink201Response {
    /**
     * 
     * @type {string}
     * @memberof PayPaymentLink201Response
     */
    checkout_url: string;
}
/**
 * 
 * @export
 * @interface PayPaymentLinkRequest
 */
export interface PayPaymentLinkRequest {
    /**
     * 
     * @type {number}
     * @memberof PayPaymentLinkRequest
     */
    amount?: number;
    /**
     * 
     * @type {string}
     * @memberof PayPaymentLinkRequest
     */
    attempt: string;
}
/**
 * 
 * @export
 * @interface Payment
 */
export interface Payment {
    /**
     * The payment link it was paid through (ADR-0030), if any.
     * @type {string}
     * @memberof Payment
     */
    payment_link?: string | null;
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
     * The payment method. `any` for a hosted checkout whose customer has not chosen yet (kept a string so clients built against 0.1.0 keep working).
     * @type {string}
     * @memberof Payment
     */
    method: string;
    /**
     * `hosted`: the customer picks the method on the Yoon page at `checkout_url`.
     * @type {string}
     * @memberof Payment
     */
    checkout?: PaymentCheckoutEnum;
    /**
     * Hosted checkout only. After this an unused checkout fails with `checkout_expired`.
     * @type {string}
     * @memberof Payment
     */
    checkout_expires_at?: string | null;
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
     * Redirect the customer here, if set. For a hosted checkout, always the Yoon page
     * (`{YOON_PUBLIC_URL}/checkout/{id}?t=…`); it forwards the customer to the provider.
     * 
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
 * @export
 */
export const PaymentCheckoutEnum = {
    Direct: 'direct',
    Hosted: 'hosted'
} as const;
export type PaymentCheckoutEnum = typeof PaymentCheckoutEnum[keyof typeof PaymentCheckoutEnum];

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
 * @interface PaymentLink
 */
export interface PaymentLink {
    /**
     * 
     * @type {string}
     * @memberof PaymentLink
     */
    id: string;
    /**
     * 
     * @type {string}
     * @memberof PaymentLink
     */
    object: PaymentLinkObjectEnum;
    /**
     * What to share: `<YOON_PUBLIC_URL>/l/<code>`. Whoever has it may pay.
     * @type {string}
     * @memberof PaymentLink
     */
    url: string;
    /**
     * An SVG QR code of `url`.
     * @type {string}
     * @memberof PaymentLink
     */
    qr_url?: string;
    /**
     * Null when the customer chooses the amount.
     * @type {number}
     * @memberof PaymentLink
     */
    amount: number | null;
    /**
     * 
     * @type {number}
     * @memberof PaymentLink
     */
    min_amount?: number | null;
    /**
     * 
     * @type {number}
     * @memberof PaymentLink
     */
    max_amount?: number | null;
    /**
     * 
     * @type {string}
     * @memberof PaymentLink
     */
    currency: string;
    /**
     * 
     * @type {string}
     * @memberof PaymentLink
     */
    country: string;
    /**
     * 
     * @type {string}
     * @memberof PaymentLink
     */
    description: string;
    /**
     * 
     * @type {string}
     * @memberof PaymentLink
     */
    method?: string | null;
    /**
     * 
     * @type {number}
     * @memberof PaymentLink
     */
    max_payments?: number | null;
    /**
     * 
     * @type {string}
     * @memberof PaymentLink
     */
    expires_at?: string | null;
    /**
     * 
     * @type {string}
     * @memberof PaymentLink
     */
    return_url?: string | null;
    /**
     * 
     * @type {boolean}
     * @memberof PaymentLink
     */
    active: boolean;
    /**
     * Succeeded payments.
     * @type {number}
     * @memberof PaymentLink
     */
    payments_count: number;
    /**
     * Sum of succeeded payments
     * @type {number}
     * @memberof PaymentLink
     */
    amount_collected: number;
    /**
     * Your own key/value pairs. At most 20 keys; keys ≤ 40 characters, values ≤ 500.
     * @type {{ [key: string]: string; }}
     * @memberof PaymentLink
     */
    metadata?: { [key: string]: string; };
    /**
     * 
     * @type {string}
     * @memberof PaymentLink
     */
    created_at: string;
}


/**
 * @export
 */
export const PaymentLinkObjectEnum = {
    PaymentLink: 'payment_link'
} as const;
export type PaymentLinkObjectEnum = typeof PaymentLinkObjectEnum[keyof typeof PaymentLinkObjectEnum];

/**
 * 
 * @export
 * @interface PaymentLinkPage
 */
export interface PaymentLinkPage {
    /**
     * 
     * @type {Array<PaymentLink>}
     * @memberof PaymentLinkPage
     */
    data: Array<PaymentLink>;
    /**
     * 
     * @type {boolean}
     * @memberof PaymentLinkPage
     */
    has_more: boolean;
    /**
     * Pass as `starting_after` to get the next page; null on the last page.
     * @type {string}
     * @memberof PaymentLinkPage
     */
    next_cursor: string | null;
}
/**
 * 
 * @export
 * @interface PaymentLinkView
 */
export interface PaymentLinkView {
    [key: string]: any | any;
    /**
     * 
     * @type {string}
     * @memberof PaymentLinkView
     */
    merchant: string | null;
    /**
     * 
     * @type {string}
     * @memberof PaymentLinkView
     */
    description: string;
    /**
     * 
     * @type {number}
     * @memberof PaymentLinkView
     */
    amount: number | null;
    /**
     * 
     * @type {number}
     * @memberof PaymentLinkView
     */
    min_amount: number | null;
    /**
     * 
     * @type {number}
     * @memberof PaymentLinkView
     */
    max_amount: number | null;
    /**
     * 
     * @type {string}
     * @memberof PaymentLinkView
     */
    currency: string;
    /**
     * `open`, `inactive`, `expired` or `used_up` (open set).
     * @type {string}
     * @memberof PaymentLinkView
     */
    state: string;
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
 * @interface Plan
 */
export interface Plan {
    /**
     * 
     * @type {string}
     * @memberof Plan
     */
    id: string;
    /**
     * 
     * @type {string}
     * @memberof Plan
     */
    object: PlanObjectEnum;
    /**
     * 
     * @type {string}
     * @memberof Plan
     */
    slug: string;
    /**
     * 
     * @type {string}
     * @memberof Plan
     */
    name: string;
    /**
     * 
     * @type {boolean}
     * @memberof Plan
     */
    active: boolean;
    /**
     * Free-form JSON (limits, flags) returned on subscriptions, so you can enforce limits from it.
     * @type {{ [key: string]: any; }}
     * @memberof Plan
     */
    features: { [key: string]: any; };
    /**
     * Your own key/value pairs. At most 20 keys; keys ≤ 40 characters, values ≤ 500.
     * @type {{ [key: string]: string; }}
     * @memberof Plan
     */
    metadata: { [key: string]: string; };
    /**
     * Oldest first
     * @type {Array<Price>}
     * @memberof Plan
     */
    prices: Array<Price>;
    /**
     * Default free trial for new subscriptions, 0 for none (ADR-0028).
     * @type {number}
     * @memberof Plan
     */
    trial_days?: number;
    /**
     * 
     * @type {string}
     * @memberof Plan
     */
    created_at: string;
    /**
     * 
     * @type {string}
     * @memberof Plan
     */
    updated_at: string;
}


/**
 * @export
 */
export const PlanObjectEnum = {
    Plan: 'plan'
} as const;
export type PlanObjectEnum = typeof PlanObjectEnum[keyof typeof PlanObjectEnum];

/**
 * 
 * @export
 * @interface PlanPage
 */
export interface PlanPage {
    /**
     * 
     * @type {Array<Plan>}
     * @memberof PlanPage
     */
    data: Array<Plan>;
    /**
     * 
     * @type {boolean}
     * @memberof PlanPage
     */
    has_more: boolean;
    /**
     * Pass as `starting_after` to get the next page; null on the last page.
     * @type {string}
     * @memberof PlanPage
     */
    next_cursor: string | null;
}
/**
 * 
 * @export
 * @interface PlanRef
 */
export interface PlanRef {
    /**
     * 
     * @type {string}
     * @memberof PlanRef
     */
    id: string;
    /**
     * 
     * @type {string}
     * @memberof PlanRef
     */
    slug: string;
    /**
     * 
     * @type {string}
     * @memberof PlanRef
     */
    name: string;
}
/**
 * 
 * @export
 * @interface PortalSession
 */
export interface PortalSession {
    /**
     * 
     * @type {string}
     * @memberof PortalSession
     */
    id: string;
    /**
     * 
     * @type {string}
     * @memberof PortalSession
     */
    object: PortalSessionObjectEnum;
    /**
     * 
     * @type {string}
     * @memberof PortalSession
     */
    customer: string;
    /**
     * Where to send the customer. Contains a secret token: do not log or store it.
     * @type {string}
     * @memberof PortalSession
     */
    url: string;
    /**
     * 
     * @type {string}
     * @memberof PortalSession
     */
    expires_at: string;
}


/**
 * @export
 */
export const PortalSessionObjectEnum = {
    PortalSession: 'portal_session'
} as const;
export type PortalSessionObjectEnum = typeof PortalSessionObjectEnum[keyof typeof PortalSessionObjectEnum];

/**
 * 
 * @export
 * @interface PortalView
 */
export interface PortalView {
    /**
     * 
     * @type {string}
     * @memberof PortalView
     */
    merchant: string | null;
    /**
     * The customer's name, if known.
     * @type {string}
     * @memberof PortalView
     */
    customer: string | null;
    /**
     * 
     * @type {string}
     * @memberof PortalView
     */
    return_url: string | null;
    /**
     * 
     * @type {string}
     * @memberof PortalView
     */
    expires_at: string;
    /**
     * 
     * @type {PortalViewAllow}
     * @memberof PortalView
     */
    allow: PortalViewAllow;
    /**
     * 
     * @type {Array<PortalViewSubscriptionsInner>}
     * @memberof PortalView
     */
    subscriptions: Array<PortalViewSubscriptionsInner>;
    /**
     * 
     * @type {Array<PortalViewInvoicesInner>}
     * @memberof PortalView
     */
    invoices: Array<PortalViewInvoicesInner>;
}
/**
 * 
 * @export
 * @interface PortalViewAllow
 */
export interface PortalViewAllow {
    /**
     * 
     * @type {boolean}
     * @memberof PortalViewAllow
     */
    cancel: boolean;
    /**
     * 
     * @type {boolean}
     * @memberof PortalViewAllow
     */
    coupons: boolean;
    /**
     * 
     * @type {Array<PortalViewAllowPricesInner>}
     * @memberof PortalViewAllow
     */
    prices: Array<PortalViewAllowPricesInner>;
}
/**
 * 
 * @export
 * @interface PortalViewAllowPricesInner
 */
export interface PortalViewAllowPricesInner {
    /**
     * 
     * @type {string}
     * @memberof PortalViewAllowPricesInner
     */
    id: string;
    /**
     * 
     * @type {string}
     * @memberof PortalViewAllowPricesInner
     */
    plan: string;
    /**
     * 
     * @type {number}
     * @memberof PortalViewAllowPricesInner
     */
    amount: number;
    /**
     * 
     * @type {string}
     * @memberof PortalViewAllowPricesInner
     */
    currency: string;
    /**
     * 
     * @type {string}
     * @memberof PortalViewAllowPricesInner
     */
    interval: string;
    /**
     * 
     * @type {number}
     * @memberof PortalViewAllowPricesInner
     */
    interval_count: number;
}
/**
 * 
 * @export
 * @interface PortalViewInvoicesInner
 */
export interface PortalViewInvoicesInner {
    /**
     * 
     * @type {string}
     * @memberof PortalViewInvoicesInner
     */
    number: string;
    /**
     * 
     * @type {string}
     * @memberof PortalViewInvoicesInner
     */
    status: string;
    /**
     * 
     * @type {number}
     * @memberof PortalViewInvoicesInner
     */
    amount_due: number;
    /**
     * 
     * @type {string}
     * @memberof PortalViewInvoicesInner
     */
    currency: string;
    /**
     * 
     * @type {string}
     * @memberof PortalViewInvoicesInner
     */
    paid_at: string | null;
    /**
     * 
     * @type {string}
     * @memberof PortalViewInvoicesInner
     */
    period_start: string | null;
    /**
     * 
     * @type {string}
     * @memberof PortalViewInvoicesInner
     */
    period_end: string | null;
    /**
     * 
     * @type {string}
     * @memberof PortalViewInvoicesInner
     */
    receipt_url: string | null;
}
/**
 * 
 * @export
 * @interface PortalViewSubscriptionsInner
 */
export interface PortalViewSubscriptionsInner {
    /**
     * 
     * @type {string}
     * @memberof PortalViewSubscriptionsInner
     */
    id: string;
    /**
     * 
     * @type {string}
     * @memberof PortalViewSubscriptionsInner
     */
    plan: string;
    /**
     * 
     * @type {number}
     * @memberof PortalViewSubscriptionsInner
     */
    amount: number;
    /**
     * 
     * @type {string}
     * @memberof PortalViewSubscriptionsInner
     */
    currency: string;
    /**
     * 
     * @type {string}
     * @memberof PortalViewSubscriptionsInner
     */
    interval: string;
    /**
     * 
     * @type {number}
     * @memberof PortalViewSubscriptionsInner
     */
    interval_count: number;
    /**
     * 
     * @type {string}
     * @memberof PortalViewSubscriptionsInner
     */
    status: string;
    /**
     * 
     * @type {boolean}
     * @memberof PortalViewSubscriptionsInner
     */
    entitled: boolean;
    /**
     * 
     * @type {string}
     * @memberof PortalViewSubscriptionsInner
     */
    paid_until: string | null;
    /**
     * 
     * @type {string}
     * @memberof PortalViewSubscriptionsInner
     */
    trial_end: string | null;
    /**
     * 
     * @type {boolean}
     * @memberof PortalViewSubscriptionsInner
     */
    cancel_at_period_end: boolean;
    /**
     * 
     * @type {string}
     * @memberof PortalViewSubscriptionsInner
     */
    pending_plan: string | null;
    /**
     * 
     * @type {string}
     * @memberof PortalViewSubscriptionsInner
     */
    discount_code: string | null;
    /**
     * 
     * @type {number}
     * @memberof PortalViewSubscriptionsInner
     */
    discount_periods_remaining: number | null;
    /**
     * 
     * @type {PortalViewSubscriptionsInnerOpenInvoice}
     * @memberof PortalViewSubscriptionsInner
     */
    open_invoice: PortalViewSubscriptionsInnerOpenInvoice;
}
/**
 * 
 * @export
 * @interface PortalViewSubscriptionsInnerOpenInvoice
 */
export interface PortalViewSubscriptionsInnerOpenInvoice {
    /**
     * 
     * @type {string}
     * @memberof PortalViewSubscriptionsInnerOpenInvoice
     */
    number: string;
    /**
     * 
     * @type {number}
     * @memberof PortalViewSubscriptionsInnerOpenInvoice
     */
    amount_due: number;
    /**
     * 
     * @type {string}
     * @memberof PortalViewSubscriptionsInnerOpenInvoice
     */
    currency: string;
    /**
     * 
     * @type {string}
     * @memberof PortalViewSubscriptionsInnerOpenInvoice
     */
    checkout_url: string | null;
}
/**
 * 
 * @export
 * @interface Price
 */
export interface Price {
    /**
     * 
     * @type {string}
     * @memberof Price
     */
    id: string;
    /**
     * 
     * @type {string}
     * @memberof Price
     */
    object: PriceObjectEnum;
    /**
     * The plan's id.
     * @type {string}
     * @memberof Price
     */
    plan: string;
    /**
     * 
     * @type {string}
     * @memberof Price
     */
    currency: string;
    /**
     * 
     * @type {number}
     * @memberof Price
     */
    amount: number;
    /**
     * 
     * @type {Interval}
     * @memberof Price
     */
    interval: Interval;
    /**
     * 
     * @type {number}
     * @memberof Price
     */
    interval_count: number;
    /**
     * 
     * @type {boolean}
     * @memberof Price
     */
    active: boolean;
    /**
     * 
     * @type {string}
     * @memberof Price
     */
    created_at: string;
}


/**
 * @export
 */
export const PriceObjectEnum = {
    Price: 'price'
} as const;
export type PriceObjectEnum = typeof PriceObjectEnum[keyof typeof PriceObjectEnum];

/**
 * 
 * @export
 * @interface PriceInput
 */
export interface PriceInput {
    /**
     * 
     * @type {string}
     * @memberof PriceInput
     */
    currency: string;
    /**
     * Minor units: XOF has none, 15 000 XOF = 15000.
     * @type {number}
     * @memberof PriceInput
     */
    amount: number;
    /**
     * 
     * @type {Interval}
     * @memberof PriceInput
     */
    interval: Interval;
    /**
     * 
     * @type {number}
     * @memberof PriceInput
     */
    interval_count?: number;
}


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
    /**
     * For `coupon_not_applicable`: `unknown`, `archived`, `expired`, `exhausted`, `plan`, `currency` (open set).
     * @type {string}
     * @memberof Problem
     */
    reason?: string;
}
/**
 * A paid invoice as its public receipt shows it. Not a tax invoice; no contact details.
 * @export
 * @interface ReceiptView
 */
export interface ReceiptView {
    [key: string]: any | any;
    /**
     * 
     * @type {string}
     * @memberof ReceiptView
     */
    number: string;
    /**
     * 
     * @type {number}
     * @memberof ReceiptView
     */
    subtotal?: number;
    /**
     * 
     * @type {number}
     * @memberof ReceiptView
     */
    discount_amount?: number;
    /**
     * 
     * @type {string}
     * @memberof ReceiptView
     */
    coupon_code?: string | null;
    /**
     * The application's name.
     * @type {string}
     * @memberof ReceiptView
     */
    merchant: string;
    /**
     * The customer's name
     * @type {string}
     * @memberof ReceiptView
     */
    customer: string;
    /**
     * 
     * @type {string}
     * @memberof ReceiptView
     */
    plan: string;
    /**
     * 
     * @type {Interval}
     * @memberof ReceiptView
     */
    interval: Interval;
    /**
     * 
     * @type {number}
     * @memberof ReceiptView
     */
    interval_count: number;
    /**
     * 
     * @type {Array<ReceiptViewLinesInner>}
     * @memberof ReceiptView
     */
    lines: Array<ReceiptViewLinesInner>;
    /**
     * Minor units.
     * @type {number}
     * @memberof ReceiptView
     */
    amount: number;
    /**
     * 
     * @type {string}
     * @memberof ReceiptView
     */
    currency: string;
    /**
     * 
     * @type {string}
     * @memberof ReceiptView
     */
    period_start: string | null;
    /**
     * 
     * @type {string}
     * @memberof ReceiptView
     */
    period_end: string | null;
    /**
     * 
     * @type {string}
     * @memberof ReceiptView
     */
    paid_at: string | null;
    /**
     * 
     * @type {string}
     * @memberof ReceiptView
     */
    method: string | null;
    /**
     * 
     * @type {string}
     * @memberof ReceiptView
     */
    payment: string | null;
}


/**
 * 
 * @export
 * @interface ReceiptViewLinesInner
 */
export interface ReceiptViewLinesInner {
    [key: string]: any | any;
    /**
     * 
     * @type {string}
     * @memberof ReceiptViewLinesInner
     */
    description: string;
    /**
     * 
     * @type {number}
     * @memberof ReceiptViewLinesInner
     */
    amount: number;
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
 * @interface RenewSubscriptionRequest
 */
export interface RenewSubscriptionRequest {
    /**
     * 
     * @type {string}
     * @memberof RenewSubscriptionRequest
     */
    return_url?: string;
}
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
     * `portal`: done by the customer from the customer portal (open set).
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
    Admin: 'admin',
    Portal: 'portal'
} as const;
export type StatusEventCauseEnum = typeof StatusEventCauseEnum[keyof typeof StatusEventCauseEnum];

/**
 * 
 * @export
 * @interface Subscription
 */
export interface Subscription {
    /**
     * 
     * @type {Discount}
     * @memberof Subscription
     */
    discount?: Discount | null;
    /**
     * 
     * @type {string}
     * @memberof Subscription
     */
    id: string;
    /**
     * 
     * @type {string}
     * @memberof Subscription
     */
    object: SubscriptionObjectEnum;
    /**
     * 
     * @type {SubscriptionCustomer}
     * @memberof Subscription
     */
    customer: SubscriptionCustomer;
    /**
     * 
     * @type {SubscriptionPrice}
     * @memberof Subscription
     */
    price: SubscriptionPrice;
    /**
     * 
     * @type {SubscriptionStatus}
     * @memberof Subscription
     */
    status: SubscriptionStatus;
    /**
     * 
     * @type {string}
     * @memberof Subscription
     */
    collection_method: SubscriptionCollectionMethodEnum;
    /**
     * 
     * @type {string}
     * @memberof Subscription
     */
    current_period_start?: string | null;
    /**
     * Null before the first payment
     * @type {string}
     * @memberof Subscription
     */
    current_period_end?: string | null;
    /**
     * 
     * @type {number}
     * @memberof Subscription
     */
    grace_period_days: number;
    /**
     * 
     * @type {boolean}
     * @memberof Subscription
     */
    cancel_at_period_end: boolean;
    /**
     * 
     * @type {SubscriptionPendingChange}
     * @memberof Subscription
     */
    pending_change?: SubscriptionPendingChange;
    /**
     * 
     * @type {string}
     * @memberof Subscription
     */
    canceled_at?: string | null;
    /**
     * Periods granted by an operator
     * @type {boolean}
     * @memberof Subscription
     */
    comp: boolean;
    /**
     * Whether the customer should have access now (`active`, `past_due` or `trialing`).
     * @type {boolean}
     * @memberof Subscription
     */
    entitled: boolean;
    /**
     * When its free trial ends (or ended); null without a trial.
     * @type {string}
     * @memberof Subscription
     */
    trial_end?: string | null;
    /**
     * The plan's features
     * @type {{ [key: string]: any; }}
     * @memberof Subscription
     */
    features: { [key: string]: any; };
    /**
     * 
     * @type {Invoice}
     * @memberof Subscription
     */
    latest_invoice?: Invoice | null;
    /**
     * Your own key/value pairs. At most 20 keys; keys ≤ 40 characters, values ≤ 500.
     * @type {{ [key: string]: string; }}
     * @memberof Subscription
     */
    metadata: { [key: string]: string; };
    /**
     * 
     * @type {string}
     * @memberof Subscription
     */
    created_at: string;
    /**
     * 
     * @type {string}
     * @memberof Subscription
     */
    updated_at: string;
}


/**
 * @export
 */
export const SubscriptionObjectEnum = {
    Subscription: 'subscription'
} as const;
export type SubscriptionObjectEnum = typeof SubscriptionObjectEnum[keyof typeof SubscriptionObjectEnum];

/**
 * @export
 */
export const SubscriptionCollectionMethodEnum = {
    PrepaidManual: 'prepaid_manual'
} as const;
export type SubscriptionCollectionMethodEnum = typeof SubscriptionCollectionMethodEnum[keyof typeof SubscriptionCollectionMethodEnum];

/**
 * 
 * @export
 * @interface SubscriptionCustomer
 */
export interface SubscriptionCustomer {
    /**
     * 
     * @type {string}
     * @memberof SubscriptionCustomer
     */
    id: string;
    /**
     * 
     * @type {string}
     * @memberof SubscriptionCustomer
     */
    external_id: string;
}
/**
 * 
 * @export
 * @interface SubscriptionPage
 */
export interface SubscriptionPage {
    /**
     * 
     * @type {Array<Subscription>}
     * @memberof SubscriptionPage
     */
    data: Array<Subscription>;
    /**
     * 
     * @type {boolean}
     * @memberof SubscriptionPage
     */
    has_more: boolean;
    /**
     * Pass as `starting_after` to get the next page; null on the last page.
     * @type {string}
     * @memberof SubscriptionPage
     */
    next_cursor: string | null;
}
/**
 * 
 * @export
 * @interface SubscriptionPendingChange
 */
export interface SubscriptionPendingChange {
    /**
     * 
     * @type {SubscriptionPrice}
     * @memberof SubscriptionPendingChange
     */
    price: SubscriptionPrice;
    /**
     * 
     * @type {string}
     * @memberof SubscriptionPendingChange
     */
    effective_at?: string | null;
}
/**
 * 
 * @export
 * @interface SubscriptionPrice
 */
export interface SubscriptionPrice {
    /**
     * 
     * @type {string}
     * @memberof SubscriptionPrice
     */
    id: string;
    /**
     * 
     * @type {string}
     * @memberof SubscriptionPrice
     */
    currency: string;
    /**
     * 
     * @type {number}
     * @memberof SubscriptionPrice
     */
    amount: number;
    /**
     * 
     * @type {Interval}
     * @memberof SubscriptionPrice
     */
    interval: Interval;
    /**
     * 
     * @type {number}
     * @memberof SubscriptionPrice
     */
    interval_count: number;
    /**
     * 
     * @type {PlanRef}
     * @memberof SubscriptionPrice
     */
    plan: PlanRef;
}



/**
 * `active`, `past_due` (in grace) and `trialing` are entitled; see `entitled`. `expired` and
 * `canceled` come back to `active` if an open invoice is paid late. Upgrade your client to
 * 0.4 before using trials: older helpers treat `trialing` as not entitled.
 * 
 * @export
 */
export const SubscriptionStatus = {
    Incomplete: 'incomplete',
    Active: 'active',
    PastDue: 'past_due',
    Expired: 'expired',
    Canceled: 'canceled',
    Trialing: 'trialing'
} as const;
export type SubscriptionStatus = typeof SubscriptionStatus[keyof typeof SubscriptionStatus];

/**
 * 
 * @export
 * @interface UpdateCouponRequest
 */
export interface UpdateCouponRequest {
    /**
     * 
     * @type {boolean}
     * @memberof UpdateCouponRequest
     */
    active?: boolean;
    /**
     * 
     * @type {number}
     * @memberof UpdateCouponRequest
     */
    max_redemptions?: number | null;
    /**
     * Your own key/value pairs. At most 20 keys; keys ≤ 40 characters, values ≤ 500.
     * @type {{ [key: string]: string; }}
     * @memberof UpdateCouponRequest
     */
    metadata?: { [key: string]: string; };
}
/**
 * 
 * @export
 * @interface UpdateCustomerRequest
 */
export interface UpdateCustomerRequest {
    /**
     * 
     * @type {string}
     * @memberof UpdateCustomerRequest
     */
    email?: string | null;
    /**
     * 
     * @type {string}
     * @memberof UpdateCustomerRequest
     */
    phone?: string | null;
    /**
     * 
     * @type {string}
     * @memberof UpdateCustomerRequest
     */
    pi_alias?: string | null;
    /**
     * 
     * @type {string}
     * @memberof UpdateCustomerRequest
     */
    country?: string;
    /**
     * 
     * @type {string}
     * @memberof UpdateCustomerRequest
     */
    name?: string | null;
    /**
     * Your own key/value pairs. At most 20 keys; keys ≤ 40 characters, values ≤ 500.
     * @type {{ [key: string]: string; }}
     * @memberof UpdateCustomerRequest
     */
    metadata?: { [key: string]: string; };
}
/**
 * 
 * @export
 * @interface UpdatePaymentLinkRequest
 */
export interface UpdatePaymentLinkRequest {
    /**
     * 
     * @type {boolean}
     * @memberof UpdatePaymentLinkRequest
     */
    active?: boolean;
    /**
     * 
     * @type {string}
     * @memberof UpdatePaymentLinkRequest
     */
    description?: string;
    /**
     * 
     * @type {string}
     * @memberof UpdatePaymentLinkRequest
     */
    expires_at?: string | null;
    /**
     * 
     * @type {number}
     * @memberof UpdatePaymentLinkRequest
     */
    max_payments?: number | null;
    /**
     * Your own key/value pairs. At most 20 keys; keys ≤ 40 characters, values ≤ 500.
     * @type {{ [key: string]: string; }}
     * @memberof UpdatePaymentLinkRequest
     */
    metadata?: { [key: string]: string; };
}
/**
 * 
 * @export
 * @interface UpdatePlanRequest
 */
export interface UpdatePlanRequest {
    /**
     * 
     * @type {string}
     * @memberof UpdatePlanRequest
     */
    name?: string;
    /**
     * 
     * @type {boolean}
     * @memberof UpdatePlanRequest
     */
    active?: boolean;
    /**
     * 
     * @type {{ [key: string]: any; }}
     * @memberof UpdatePlanRequest
     */
    features?: { [key: string]: any; };
    /**
     * Your own key/value pairs. At most 20 keys; keys ≤ 40 characters, values ≤ 500.
     * @type {{ [key: string]: string; }}
     * @memberof UpdatePlanRequest
     */
    metadata?: { [key: string]: string; };
    /**
     * 
     * @type {number}
     * @memberof UpdatePlanRequest
     */
    trial_days?: number;
}
/**
 * 
 * @export
 * @interface UpdatePriceRequest
 */
export interface UpdatePriceRequest {
    /**
     * 
     * @type {boolean}
     * @memberof UpdatePriceRequest
     */
    active: boolean;
}
