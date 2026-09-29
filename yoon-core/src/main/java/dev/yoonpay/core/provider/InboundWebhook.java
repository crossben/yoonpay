package dev.yoonpay.core.provider;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A provider callback exactly as received: raw body bytes (signatures are over them) and headers. */
public record InboundWebhook(Map<String, List<String>> headers, byte[] rawBody) {

    public InboundWebhook {
        headers = Map.copyOf(headers);
        rawBody = rawBody.clone();
    }

    @Override
    public byte[] rawBody() {
        return rawBody.clone();
    }

    /** First value of a header, case-insensitive; null if absent. */
    public String header(String name) {
        for (var e : headers.entrySet()) {
            if (e.getKey().toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT)) && !e.getValue().isEmpty()) {
                return e.getValue().getFirst();
            }
        }
        return null;
    }
}
