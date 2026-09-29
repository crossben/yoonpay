package dev.yoonpay.server.outbox;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

/** An outbound event with its delivery state. {@code payload} is exactly what was (or will be) POSTed. */
public record EventResponse(String id, String object, String type, String resourceType, String resourceId,
                            Instant createdAt, Delivery delivery, JsonNode payload) {

    public record Delivery(String status, int attempts, Instant nextAttemptAt, Integer lastStatusCode,
                           String lastError, Instant deliveredAt) {
    }

    public static EventResponse of(EventRecord e, ObjectMapper json) {
        String status = e.deliveryStatus().toLowerCase();
        return new EventResponse(e.id(), "event", e.type(), e.resourceType(), e.resourceId(), e.createdAt(),
                new Delivery(status, e.attempts(), status.equals("pending") ? e.nextAttemptAt() : null,
                        e.lastStatusCode(), e.lastError(), e.deliveredAt()),
                json.readTree(e.payload()));
    }
}
