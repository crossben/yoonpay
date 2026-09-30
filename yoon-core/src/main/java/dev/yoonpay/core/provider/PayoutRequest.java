package dev.yoonpay.core.provider;

import dev.yoonpay.core.money.Money;

import java.net.URI;
import java.util.Objects;

/**
 * @param recipientPhone E.164; adapters convert to the provider's local format. May be null when
 *                       {@code recipientAlias} identifies the recipient instead.
 * @param recipientAlias the recipient's payment alias on an alias-based rail (PI-SPI), or null
 */
public record PayoutRequest(
        String attemptReference,
        Money amount,
        String country,
        String method,
        String recipientPhone,
        URI callbackUrl,
        String recipientAlias) {

    public PayoutRequest {
        Objects.requireNonNull(attemptReference, "attemptReference");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(country, "country");
        Objects.requireNonNull(method, "method");
        if (recipientPhone == null && recipientAlias == null) {
            throw new IllegalArgumentException("a payout needs a recipient phone or alias");
        }
    }

    /** Without a recipient alias: every provider that is not alias-based. */
    public PayoutRequest(String attemptReference, Money amount, String country, String method, String recipientPhone,
                         URI callbackUrl) {
        this(attemptReference, amount, country, method, recipientPhone, callbackUrl, null);
    }
}
