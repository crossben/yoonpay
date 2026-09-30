package dev.yoonpay.client;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * Verifies {@code Yoon-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, "<t>.<raw body>")>}.
 * Pass the raw request body exactly as received.
 */
public final class WebhookSignature {

    public static final String HEADER = "Yoon-Signature";
    public static final long TOLERANCE_SECONDS = 300;

    private WebhookSignature() {
    }

    public static boolean verify(String secret, String header, String rawBody) {
        return verify(secret, header, rawBody, Instant.now().getEpochSecond());
    }

    public static boolean verify(String secret, String header, String rawBody, long nowSeconds) {
        if (secret == null || secret.isEmpty() || header == null) {
            return false;
        }
        long t = -1;
        String v1 = null;
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if (kv[0].equals("t")) {
                try {
                    t = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (kv[0].equals("v1")) {
                v1 = kv[1].toLowerCase();
            }
        }
        if (t < 0 || v1 == null || Math.abs(nowSeconds - t) > TOLERANCE_SECONDS) {
            return false;
        }
        return MessageDigest.isEqual(hmac(secret, t + "." + rawBody).getBytes(StandardCharsets.UTF_8),
                v1.getBytes(StandardCharsets.UTF_8));
    }

    private static String hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            StringBuilder hex = new StringBuilder();
            for (byte b : mac.doFinal(data.getBytes(StandardCharsets.UTF_8))) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
