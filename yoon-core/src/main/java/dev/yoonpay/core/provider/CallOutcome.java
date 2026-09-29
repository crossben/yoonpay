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
     */
    record Accepted(ProviderReference reference, URI checkoutUrl, String instructions) implements CallOutcome {
    }

    /**
     * Definite refusal: the provider answered no, or the request was provably never sent
     * (connection refused, open circuit).
     */
    record Rejected(String code, String message) implements CallOutcome {
    }

    /** Possibly accepted. Resolve through the status API; never retry, never fail over. */
    record Unknown(String cause) implements CallOutcome {
    }
}
