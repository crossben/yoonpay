package dev.yoonpay.provider.naboopay;

import dev.yoonpay.core.lifecycle.PaymentStatus;

import java.util.Locale;

/**
 * NabooPay's {@code transaction_status} → Yoon's. The SDK documents pending, paid, done and
 * part_paid; the failure spellings are those seen or defended against in production. Unknown
 * values map to null: "no answer", never a guess.
 */
final class NabooPayStatus {

    private NabooPayStatus() {
    }

    static PaymentStatus of(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "paid", "done" -> PaymentStatus.SUCCEEDED;
            // part_paid: some money arrived, not the price — never settled as paid.
            case "pending", "part_paid" -> PaymentStatus.PENDING;
            case "cancel", "cancelled", "canceled", "failed", "failure", "rejected", "refused", "error" -> PaymentStatus.FAILED;
            case "expired" -> PaymentStatus.EXPIRED;
            default -> null;
        };
    }
}
