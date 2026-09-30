package dev.yoonpay.server.payout;

import java.time.Instant;
import java.util.UUID;

/** A {@code payouts} row. */
public record PayoutRecord(
        String id,
        UUID applicationId,
        String status,
        long amount,
        String currency,
        String country,
        String method,
        String reference,
        String recipientPhone,
        String provider,
        String providerReference,
        String routingReason,
        boolean needsReview,
        String failureCode,
        String failureMessage,
        Instant createdAt,
        Instant updatedAt,
        String recipientPiAlias) {
}
