package dev.yoonpay.core.provider;

import dev.yoonpay.core.money.Money;

import java.net.URI;
import java.util.Objects;

/** @param recipientPhone E.164; adapters convert to the provider's local format */
public record PayoutRequest(
        String attemptReference,
        Money amount,
        String country,
        String method,
        String recipientPhone,
        URI callbackUrl) {

    public PayoutRequest {
        Objects.requireNonNull(attemptReference, "attemptReference");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(country, "country");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(recipientPhone, "recipientPhone");
    }
}
