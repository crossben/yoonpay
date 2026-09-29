package dev.yoonpay.server.outbox;

import java.time.Instant;
import java.util.UUID;

/** An {@code outbound_events} row. */
public record EventRecord(
        String id,
        UUID applicationId,
        String type,
        String resourceType,
        String resourceId,
        String payload,
        Instant createdAt,
        String deliveryStatus,
        int attempts,
        Instant nextAttemptAt,
        String lastError,
        Integer lastStatusCode,
        Instant deliveredAt) {
}
