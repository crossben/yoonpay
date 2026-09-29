package dev.yoonpay.provider.dexpay;

import dev.yoonpay.core.lifecycle.PaymentStatus;
import dev.yoonpay.core.lifecycle.PayoutStatus;

import java.util.Locale;

/** DexPay's raw statuses → Yoon's. Unknown values map to null: "no answer", never a guess. */
final class DexPayStatus {

    private DexPayStatus() {
    }

    static PaymentStatus checkout(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "success", "succeeded", "completed", "paid" -> PaymentStatus.SUCCEEDED;
            case "pending", "initiated", "processing", "pending_confirmation" -> PaymentStatus.PENDING;
            case "failed", "error", "declined", "cancelled", "canceled" -> PaymentStatus.FAILED;
            case "expired" -> PaymentStatus.EXPIRED;
            // "refunded": money came in and went back outside Yoon — a human must look.
            default -> null;
        };
    }

    static PayoutStatus payout(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "completed", "success", "successful" -> PayoutStatus.PAID;
            case "failed", "cancelled", "canceled", "rejected" -> PayoutStatus.FAILED;
            case "pending", "processing", "frozen" -> PayoutStatus.PROCESSING;
            default -> null;
        };
    }
}
