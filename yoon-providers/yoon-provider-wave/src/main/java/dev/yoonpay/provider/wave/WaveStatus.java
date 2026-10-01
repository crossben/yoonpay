package dev.yoonpay.provider.wave;

import dev.yoonpay.core.lifecycle.PaymentStatus;
import dev.yoonpay.core.lifecycle.PayoutStatus;

import java.util.Locale;

/** Wave's raw statuses → Yoon's. Unknown values map to null: "no answer", never a guess. */
final class WaveStatus {

    private WaveStatus() {
    }

    /** A checkout session: {@code payment_status}, and {@code checkout_status} to tell an expired session. */
    static PaymentStatus checkout(String paymentStatus, String checkoutStatus) {
        if (paymentStatus == null) {
            return null;
        }
        String checkout = checkoutStatus == null ? "" : checkoutStatus.trim().toLowerCase(Locale.ROOT);
        return switch (paymentStatus.trim().toLowerCase(Locale.ROOT)) {
            case "succeeded" -> PaymentStatus.SUCCEEDED;
            case "cancelled" -> PaymentStatus.FAILED;
            case "processing" -> checkout.equals("expired") ? PaymentStatus.EXPIRED : PaymentStatus.PENDING;
            default -> null;
        };
    }

    static PayoutStatus payout(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "processing" -> PayoutStatus.PROCESSING;
            case "succeeded" -> PayoutStatus.PAID;
            case "failed" -> PayoutStatus.FAILED;
            // "reversed": the money left and came back outside Yoon — a human must look.
            default -> null;
        };
    }
}
