package dev.yoonpay.core.routing;

import dev.yoonpay.core.provider.Operation;
import dev.yoonpay.core.provider.ProviderId;

import java.util.Currency;
import java.util.Objects;

/**
 * What the application wants, not who should do it.
 *
 * @param pinned optional override: the application insists on this provider
 */
public record RouteRequest(Operation operation, String country, String method, Currency currency, ProviderId pinned) {

    public RouteRequest {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(country, "country");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(currency, "currency");
    }
}
