package dev.yoonpay.server.lifecycle;

import java.time.Instant;

/** One entry of a resource's status history. {@code decision} "ignore" = recorded, not applied. */
public record EventResponse(String fromStatus, String toStatus, String decision, String cause,
                            String rawStatus, String detail, Instant createdAt) {

    public static EventResponse of(StatusEvents.Event e) {
        return new EventResponse(lower(e.fromStatus()), lower(e.toStatus()), lower(e.decision()), e.cause(),
                e.rawStatus(), e.detail(), e.createdAt());
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase();
    }
}
