package dev.yoonpay.provider.paydunya;

import dev.yoonpay.core.lifecycle.PaymentStatus;
import dev.yoonpay.core.lifecycle.PayoutStatus;

import java.util.Locale;

/** PayDunya's raw statuses → Yoon's. Unknown values map to null: "no answer", never a guess. */
final class PayDunyaStatus {

    private PayDunyaStatus() {
    }

    /** {@code checkout-invoice/confirm} statuses. */
    static PaymentStatus invoice(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "pending" -> PaymentStatus.PENDING;
            case "completed" -> PaymentStatus.SUCCEEDED;
            case "cancelled", "canceled", "failed" -> PaymentStatus.FAILED;
            case "expired" -> PaymentStatus.EXPIRED;
            default -> null;
        };
    }

    /** {@code disburse/check-status} statuses. */
    static PayoutStatus disburse(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "success", "completed" -> PayoutStatus.PAID;
            case "failed", "failure", "cancelled", "canceled", "declined", "rejected", "error" -> PayoutStatus.FAILED;
            case "pending", "processing", "created" -> PayoutStatus.PROCESSING;
            default -> null;
        };
    }
}
