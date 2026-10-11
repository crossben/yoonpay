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

    // RFC 9562 §6.2 method 1: the 12 bits after the timestamp are a counter within the millisecond,
    // so ids made in the same millisecond (a plan and its prices) still sort in creation order.
    private static long lastMillis = -1;
    private static int sequence;

    static String uuidV7Hex(long epochMillis) {
        long millis;
        int seq;
        synchronized (Ids.class) {
            if (epochMillis > lastMillis) {
                lastMillis = epochMillis;
                sequence = RANDOM.nextInt(0x800); // start low: room to count up within the millisecond
            } else if (++sequence > 0xFFF) {
                lastMillis++;                     // counter exhausted (or clock went back): borrow the next ms
                sequence = 0;
            }
            millis = lastMillis;
            seq = sequence;
        }
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        for (int i = 0; i < 6; i++) {
            b[i] = (byte) (millis >>> (40 - 8 * i));
        }
        b[6] = (byte) (0x70 | (seq >>> 8));       // version 7 + counter high bits
        b[7] = (byte) seq;                        // counter low bits
        b[8] = (byte) ((b[8] & 0x3f) | 0x80);     // IETF variant
        return HexFormat.of().formatHex(b);
    }
}
