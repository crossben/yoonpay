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
        Instant updatedAt) {

    public static RefundResponse of(RefundRecord r) {
        return new RefundResponse(r.id(), "refund", r.paymentId(), r.status().toLowerCase(), r.amount(), r.currency(),
                r.reason(), r.provider(), r.providerReference(),
                r.failureCode() == null ? null : new Failure(r.failureCode(), r.failureMessage()),
                r.createdAt(), r.updatedAt());
    }
}
