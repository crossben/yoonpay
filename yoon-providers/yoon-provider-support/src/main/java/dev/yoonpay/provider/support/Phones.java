package dev.yoonpay.provider.support;

/** Converts Yoon's E.164 numbers to the national format some providers require. */
public final class Phones {

    private Phones() {
    }

    /** {@code +221771234567} → {@code 771234567} for Senegal; other countries unchanged without '+'. */
    public static String national(String e164, String country) {
        if (e164 == null) {
            return null;
        }
        String digits = e164.startsWith("+") ? e164.substring(1) : e164;
        String prefix = switch (country) {
            case "SN" -> "221";
            case "CI" -> "225";
            default -> null;
        };
        return prefix != null && digits.startsWith(prefix) ? digits.substring(prefix.length()) : digits;
    }
}
