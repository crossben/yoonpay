package dev.yoonpay.provider.cinetpay;

import dev.yoonpay.core.lifecycle.PaymentStatus;
import dev.yoonpay.core.lifecycle.PayoutStatus;

/** CinetPay's raw statuses → Yoon's. Unknown values map to null: "no answer", never a guess. */
final class CinetPayStatus {

    private CinetPayStatus() {
    }

    static PaymentStatus payment(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim()) {
            case "SUCCESS" -> PaymentStatus.SUCCEEDED;
            case "INITIATED", "PENDING" -> PaymentStatus.PENDING;
            case "FAILED" -> PaymentStatus.FAILED;
            case "EXPIRED" -> PaymentStatus.EXPIRED;
            default -> null;
        };
    }

    static PayoutStatus transfer(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim()) {
            case "SUCCESS" -> PayoutStatus.PAID;
            case "INITIATED", "PENDING" -> PayoutStatus.PROCESSING;
            case "FAILED" -> PayoutStatus.FAILED;
            default -> null;
        };
    }
}
