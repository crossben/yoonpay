package dev.yoonpay.core.provider;

import java.util.Objects;

/** The provider's own id for a payment, payout or refund (invoice token, transaction id…). */
public record ProviderReference(String value) {

    public ProviderReference {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("provider reference is blank");
        }
    }
}
