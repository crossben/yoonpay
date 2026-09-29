package dev.yoonpay.server.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 of the request's method, path and raw body: what "same request" means for idempotency. */
public final class RequestHash {

    private RequestHash() {
    }

    public static String of(String method, String path, byte[] body) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update((method + ' ' + path + '\n').getBytes(StandardCharsets.UTF_8));
            sha.update(body);
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
