package dev.yoonpay.server.link;

import java.time.Instant;
import java.util.UUID;

/** A {@code payment_links} row (ADR-0030). */
public record PaymentLinkRecord(
        String id,
        UUID applicationId,
        String code,
        Long amount,
        Long minAmount,
        Long maxAmount,
        String currency,
        String country,
        String description,
        String method,
        Integer maxPayments,
        Instant expiresAt,
        String returnUrl,
        boolean active,
        String metadata,
        Instant createdAt,
        Instant updatedAt) {
}
