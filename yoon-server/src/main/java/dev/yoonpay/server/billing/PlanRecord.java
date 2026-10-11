package dev.yoonpay.server.billing;

import java.time.Instant;
import java.util.UUID;

/** A {@code plans} row; {@code features} and {@code metadata} are the stored JSON text. */
public record PlanRecord(
        String id,
        UUID applicationId,
        String slug,
        String name,
        boolean active,
        String features,
        String metadata,
        Instant createdAt,
        Instant updatedAt) {
}
