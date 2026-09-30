package dev.yoonpay.core.provider;

import dev.yoonpay.core.money.Money;

import java.net.URI;
import java.util.Objects;

/**
 * @param attemptReference Yoon's own reference for this attempt; sent to the provider as
 *                         its idempotency / merchant reference where the API has one
 * @param customerPhone    E.164, may be null for card/hosted checkout
 * @param customerAlias    the customer's payment alias on an alias-based rail (PI-SPI), or null
 */
public record CollectRequest(
        String attemptReference,
        Money amount,
        String country,
        String method,
        String customerPhone,
        String description,
        URI returnUrl,
        URI callbackUrl,
        String customerAlias) {

    public CollectRequest {
        Objects.requireNonNull(attemptReference, "attemptReference");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(country, "country");
        Objects.requireNonNull(method, "method");
    }

    /** Without a customer alias: every provider that is not alias-based. */
    public CollectRequest(String attemptReference, Money amount, String country, String method, String customerPhone,
                          String description, URI returnUrl, URI callbackUrl) {
        this(attemptReference, amount, country, method, customerPhone, description, returnUrl, callbackUrl, null);
    }
}
