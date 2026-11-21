package dev.yoonpay.server.refund;

import dev.yoonpay.server.payment.PaymentResponse.Failure;

import java.time.Instant;

public record RefundResponse(
        String id,
        String object,
        String paymentId,
        String status,
        long amount,
        String currency,
        String reason,
        String provider,
        String providerReference,
        Failure failure,
        Instant createdAt,
        Instant updatedAt,
        boolean needsReview,
        boolean holdsAmount) {

    public static RefundResponse of(RefundRecord r) {
        // A failed refund the provider saw keeps its amount until an operator releases it (plan D1).
        boolean holds = r.status().equals("FAILED") && r.providerReference() != null && r.amountReleasedAt() == null;
        return new RefundResponse(r.id(), "refund", r.paymentId(), r.status().toLowerCase(), r.amount(), r.currency(),
                r.reason(), r.provider(), r.providerReference(),
                r.failureCode() == null ? null : new Failure(r.failureCode(), r.failureMessage()),
                r.createdAt(), r.updatedAt(), r.needsReview(), holds);
    }
}
