package dev.yoonpay.core.provider;

import java.util.Currency;
import java.util.Objects;

/**
 * One thing a provider can do: an operation, for a country (ISO 3166 alpha-2), a
 * payment method (e.g. {@code wave}, {@code orange_money}, {@code card}) and a currency.
 */
public record Capability(Operation operation, String country, String method, Currency currency) {

    public Capability {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(country, "country");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(currency, "currency");
    }
}
