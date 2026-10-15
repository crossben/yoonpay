package dev.yoonpay.server.portal;

import java.time.Instant;
import java.util.UUID;

/** A {@code portal_sessions} row (ADR-0031). {@code changePrices}: comma-separated, null for none. */
public record PortalSessionRecord(
        String id,
        UUID applicationId,
        String customerId,
        String tokenHash,
        boolean allowCancel,
        String changePrices,
        boolean allowCoupons,
        String returnUrl,
        Instant expiresAt,
        Instant createdAt) {
}
