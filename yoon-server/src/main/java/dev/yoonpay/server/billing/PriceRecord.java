package dev.yoonpay.server.billing;

import dev.yoonpay.core.billing.Interval;

import java.time.Instant;
import java.util.UUID;

/** A {@code prices} row. Immutable apart from {@code active} (a database trigger enforces it). */
public record PriceRecord(
        String id,
        UUID applicationId,
        String planId,
        String currency,
        long amount,
        String interval,
        int intervalCount,
        boolean active,
        Instant createdAt) {

    public Interval intervalEnum() {
        return Interval.valueOf(interval);
    }
}
