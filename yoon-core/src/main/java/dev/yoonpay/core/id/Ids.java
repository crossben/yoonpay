package dev.yoonpay.core.id;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;

/**
 * Public ids such as {@code pay_0199a3f0c2e47a1b9c3d5e6f7a8b9c0d}: a type prefix and a
 * UUIDv7 in hex. The time-ordered prefix makes ids sort by creation, which keeps cursor
 * pagination and indexes simple.
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static String payment() {
        return next("pay");
    }

    public static String refund() {
        return next("re");
    }

    public static String payout() {
        return next("po");
    }

    public static String attempt() {
        return next("att");
    }

    public static String next(String prefix) {
        return prefix + "_" + uuidV7Hex(Clock.systemUTC().millis());
    }

    static String uuidV7Hex(long epochMillis) {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        for (int i = 0; i < 6; i++) {
            b[i] = (byte) (epochMillis >>> (40 - 8 * i));
        }
        b[6] = (byte) ((b[6] & 0x0f) | 0x70); // version 7
        b[8] = (byte) ((b[8] & 0x3f) | 0x80); // IETF variant
        return HexFormat.of().formatHex(b);
    }
}
