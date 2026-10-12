package dev.yoonpay.provider.support;

import java.util.Locale;
import java.util.Map;

/**
 * An application's credentials for one provider. Keys are matched case-insensitively and
 * ignoring '-', '_' and '.': written {@code master-key} in YAML, an environment variable
 * {@code …_CREDENTIALS_MASTER_KEY} reaches the map as {@code master.key} (Spring turns each
 * '_' into a '.'), and both must match. Values are never printed.
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
        return key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").replace(".", "");
    }

    @Override
    public String toString() {
        return "Credentials[" + provider + ", keys=" + values.keySet() + " (values hidden)]";
    }
}
