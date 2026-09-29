package dev.yoonpay.server.phone;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import dev.yoonpay.server.web.ApiProblem;

/** Phone numbers are stored in E.164 and never shown or logged in full. */
public final class Phones {

    private static final PhoneNumberUtil UTIL = PhoneNumberUtil.getInstance();

    private Phones() {
    }

    /** Parses and validates a number for {@code country}; returns E.164 or throws a 400 problem. */
    public static String normalize(String raw, String country) {
        try {
            Phonenumber.PhoneNumber n = UTIL.parse(raw, country);
            if (!UTIL.isValidNumber(n)) {
                throw ApiProblem.invalid("Invalid phone number");
            }
            return UTIL.format(n, PhoneNumberUtil.PhoneNumberFormat.E164);
        } catch (NumberParseException e) {
            throw ApiProblem.invalid("Invalid phone number");
        }
    }

    /** Parses a number that must already be international ({@code +…}); returns E.164. */
    public static String normalizeE164(String raw) {
        if (raw == null || !raw.startsWith("+")) {
            throw ApiProblem.invalid("Phone number must be in international format, e.g. +221771234567");
        }
        return normalize(raw, "ZZ");
    }

    /** {@code +221771234545} → {@code +22177***45}. */
    public static String mask(String e164) {
        if (e164 == null) {
            return null;
        }
        if (e164.length() < 8) {
            return "***";
        }
        return e164.substring(0, 6) + "***" + e164.substring(e164.length() - 2);
    }
}
