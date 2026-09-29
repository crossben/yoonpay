package dev.yoonpay.server.refund;

import java.time.Instant;
import java.util.UUID;

/** A {@code refunds} row. */
public record RefundRecord(
        String id,
        UUID applicationId,
        String paymentId,
        String status,
        long amount,
        String currency,
        String reason,
        String provider,
        String providerReference,
        String failureCode,
        String failureMessage,
        Instant createdAt,
        Instant updatedAt) {
}
