package dev.yoonpay.provider.support;

import java.util.Locale;
import java.util.Map;

/**
 * An application's credentials for one provider. Keys are matched case-insensitively and
 * ignoring '-' and '_' (env vars arrive as {@code MASTERKEY}, {@code MASTER_KEY} or
 * {@code master-key}). Values are never printed.
 */
public final class Credentials {

    private final String provider;
    private final Map<String, String> values;

    public Credentials(String provider, Map<String, String> raw) {
        this.provider = provider;
        this.values = raw.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                e -> normalize(e.getKey()), Map.Entry::getValue, (a, b) -> a));
    }

    public String require(String key) {
        String v = values.get(normalize(key));
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(provider + ": missing credential '" + key + "'");
        }
        return v;
    }

    public String optional(String key, String fallback) {
        String v = values.get(normalize(key));
        return v == null || v.isBlank() ? fallback : v;
    }

    private static String normalize(String key) {
        return key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }

    @Override
    public String toString() {
        return "Credentials[" + provider + ", keys=" + values.keySet() + " (values hidden)]";
    }
}
