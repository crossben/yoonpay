package dev.yoonpay.provider.pispi;

import dev.yoonpay.core.lifecycle.PaymentStatus;
import dev.yoonpay.core.lifecycle.PayoutStatus;
import dev.yoonpay.core.lifecycle.RefundStatus;

/** PI-SPI's raw statuses → Yoon's. Unknown values map to null: "no answer", never a guess. */
final class PiSpiStatus {

    private PiSpiStatus() {
    }

    /** {@code statut} of a payment request ({@code /demandes-paiements/{txId}}). */
    static PaymentStatus request(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim()) {
            case "INITIE", "ENVOYE" -> PaymentStatus.PENDING;
            case "IRREVOCABLE" -> PaymentStatus.SUCCEEDED;
            case "REJETE", "ANNULE" -> PaymentStatus.FAILED;
            default -> null;
        };
    }

    /** {@code statut} of a sent payment ({@code /paiements-envoyes/{txId}}). */
    static PayoutStatus payout(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim()) {
            case "INITIE", "ENVOYE" -> PayoutStatus.PROCESSING;
            case "IRREVOCABLE" -> PayoutStatus.PAID;
            case "REJETE", "ANNULE" -> PayoutStatus.FAILED;
            default -> null;
        };
    }

    /** {@code retourStatut} of a received payment ({@code /paiements/{end2endId}}). */
    static RefundStatus refund(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim()) {
            case "INITIE", "ENVOYE" -> RefundStatus.PENDING;
            case "IRREVOCABLE" -> RefundStatus.REFUNDED;
            case "REJETE" -> RefundStatus.FAILED;
            default -> null;
        };
    }
}
