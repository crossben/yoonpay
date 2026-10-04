package dev.yoonpay.provider.stripe;

import dev.yoonpay.core.lifecycle.PaymentStatus;
import dev.yoonpay.core.lifecycle.RefundStatus;

/** Stripe's raw statuses → Yoon's. Unknown values map to null: "no answer", never a guess. */
final class StripeStatus {

    private StripeStatus() {
    }

    /** A Checkout Session: {@code status} and {@code payment_status}. */
    static PaymentStatus checkout(String status, String paymentStatus) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case "open" -> PaymentStatus.PENDING;
            // "complete" with "unpaid": a delayed payment method is still settling.
            case "complete" -> switch (paymentStatus == null ? "" : paymentStatus) {
                case "paid" -> PaymentStatus.SUCCEEDED;
                case "unpaid" -> PaymentStatus.PENDING;
                default -> null;
            };
            case "expired" -> PaymentStatus.EXPIRED;
            default -> null;
        };
    }

    static RefundStatus refund(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw) {
            case "pending", "requires_action" -> RefundStatus.PENDING;
            case "succeeded" -> RefundStatus.REFUNDED;
            case "failed", "canceled" -> RefundStatus.FAILED;
            default -> null;
        };
    }
}
