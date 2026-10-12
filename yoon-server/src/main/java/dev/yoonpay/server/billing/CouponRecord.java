package dev.yoonpay.server.billing;

import java.time.Instant;
import java.util.UUID;

/** A {@code coupons} row (ADR-0029). {@code planIds}: comma-separated, null for every plan. */
public record CouponRecord(
        String id,
        UUID applicationId,
        String code,
        Integer percentOff,
        Long amountOff,
        String currency,
        String duration,
        Integer durationInPeriods,
        Integer maxRedemptions,
        int timesRedeemed,
        Instant redeemBy,
        String planIds,
        boolean active,
        String metadata,
        Instant createdAt,
        Instant updatedAt) {
}
