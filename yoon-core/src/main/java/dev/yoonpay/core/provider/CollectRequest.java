package dev.yoonpay.core.provider;

import dev.yoonpay.core.money.Money;

import java.net.URI;
import java.util.Objects;

/**
 * @param attemptReference Yoon's own reference for this attempt; sent to the provider as
 *                         its idempotency / merchant reference where the API has one
 * @param customerPhone    E.164, may be null for card/hosted checkout
 */
public record CollectRequest(
        String attemptReference,
        Money amount,
        String country,
        String method,
        String customerPhone,
        String description,
        URI returnUrl,
        URI callbackUrl) {

    public CollectRequest {
        Objects.requireNonNull(attemptReference, "attemptReference");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(country, "country");
        Objects.requireNonNull(method, "method");
    }
}
