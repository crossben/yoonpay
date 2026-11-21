package dev.yoonpay.core.provider;

import java.net.URI;

/**
 * The result of a mutating provider call: exactly three outcomes.
 *
 * <p>Adapters MUST return {@link Unknown} for a timeout, 5xx, dropped connection or
 * unparseable response after the request may have been sent. Only {@link Rejected}
 * allows failover or marking the attempt FAILED.
 */
public sealed interface CallOutcome {

    /**
     * The provider took the request.
     *
     * @param checkoutUrl  hosted checkout to redirect the customer to, if any
     * @param instructions push/USSD instruction to show the customer, if any
     * @param completed    the provider's answer itself confirms the operation is done (Wave answers a
     *                     refund with 200 only once the payment is refunded) — the status API need not
     *                     be asked. Honoured for refunds only (ADR-0033).
     */
    record Accepted(ProviderReference reference, URI checkoutUrl, String instructions, boolean completed)
            implements CallOutcome {

        public Accepted(ProviderReference reference, URI checkoutUrl, String instructions) {
            this(reference, checkoutUrl, instructions, false);
        }

        /** Accepted and already done: the answer is the confirmation (ADR-0033). */
        public static Accepted completed(ProviderReference reference) {
            return new Accepted(reference, null, null, true);
        }
    }

    /**
     * Definite refusal: the provider answered no, or the request was provably never sent
     * (connection refused, open circuit).
     */
    record Rejected(String code, String message) implements CallOutcome {
    }

    /**
     * Possibly accepted. Resolve through the status API; never retry, never fail over.
     *
     * @param reference the provider's reference when the adapter already knows it (e.g. from an
     *                  earlier step, or because the provider keys on Yoon's own reference); null
     *                  otherwise — reconciliation then recovers it through {@code lookup}
     */
    record Unknown(String cause, ProviderReference reference) implements CallOutcome {
        public Unknown(String cause) {
            this(cause, null);
        }
    }
}
