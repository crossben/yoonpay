package dev.yoonpay.server.billing;

import java.time.Instant;
import java.util.UUID;

/** A {@code customers} row; {@code metadata} is the stored JSON text. */
public record CustomerRecord(
        String id,
        UUID applicationId,
        String externalId,
        String email,
        String phone,
        String piAlias,
        String country,
        String name,
        String metadata,
        Instant createdAt,
        Instant updatedAt) {
}
