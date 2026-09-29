package dev.yoonpay.core.provider;

import java.util.Currency;
import java.util.Set;

/** The capability matrix a provider declares; routing filters on it. */
public record Capabilities(Set<Capability> supported) {

    public Capabilities {
        supported = Set.copyOf(supported);
    }

    public boolean supports(Operation operation, String country, String method, Currency currency) {
        return supported.contains(new Capability(operation, country, method, currency));
    }
}
