package dev.yoonpay.server.outbox;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class EventRepository {

    private final JdbcClient jdbc;

    public EventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<EventRecord> find(UUID app, String id) {
        return jdbc.sql("SELECT * FROM outbound_events WHERE application_id = :app AND id = :id")
                .param("app", app).param("id", id).query(EventRecord.class).optional();
    }

    /** @param app null: every application (admin) */
    public List<EventRecord> list(UUID app, String type, String deliveryStatus, String resourceId,
                                  String startingAfter, int limit) {
        return jdbc.sql("""
                        SELECT * FROM outbound_events
                        WHERE (CAST(:app AS UUID) IS NULL OR application_id = :app)
                          AND (CAST(:type AS TEXT) IS NULL OR type = :type)
                          AND (CAST(:status AS TEXT) IS NULL OR delivery_status = :status)
                          AND (CAST(:resource AS TEXT) IS NULL OR resource_id = :resource)
                          AND (CAST(:cursor AS TEXT) IS NULL OR id < :cursor)
                        ORDER BY id DESC LIMIT :limit""")
                .param("app", app).param("type", type).param("status", deliveryStatus)
                .param("resource", resourceId).param("cursor", startingAfter).param("limit", limit + 1)
                .query(EventRecord.class).list();
    }

    /** Queues an event for delivery again, now. Returns false if it has no endpoint to go to. */
    public boolean requeue(String id) {
        return jdbc.sql("""
                        UPDATE outbound_events
                        SET delivery_status = 'PENDING', attempts = 0, next_attempt_at = now(), locked_until = NULL
                        WHERE id = :id AND delivery_status <> 'NO_ENDPOINT'""")
                .param("id", id).update() == 1;
    }
}
