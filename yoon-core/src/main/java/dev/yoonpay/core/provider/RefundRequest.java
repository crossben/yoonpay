package dev.yoonpay.core.provider;

import dev.yoonpay.core.money.Money;

import java.util.Objects;

public record RefundRequest(String attemptReference, ProviderReference payment, Money amount, String reason) {

    public RefundRequest {
        Objects.requireNonNull(attemptReference, "attemptReference");
        Objects.requireNonNull(payment, "payment");
        Objects.requireNonNull(amount, "amount");
    }
}
