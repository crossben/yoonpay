package dev.yoonpay.core.provider;

import java.util.Objects;
import java.util.regex.Pattern;

/** Stable provider identifier used in config, URLs and ledger accounts, e.g. {@code paydunya}. */
public record ProviderId(String value) {

    private static final Pattern VALID = Pattern.compile("[a-z][a-z0-9-]{1,31}");

    public ProviderId {
        Objects.requireNonNull(value, "value");
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid provider id: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
