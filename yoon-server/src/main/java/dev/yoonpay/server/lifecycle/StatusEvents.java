package dev.yoonpay.server.lifecycle;

import dev.yoonpay.core.lifecycle.Decision;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The append-only status history behind payments, refunds and payouts. */
@Repository
public class StatusEvents {

    private final JdbcClient jdbc;

    public StatusEvents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public enum Cause { api, provider_call, webhook, sweep, admin }

    public record Event(String fromStatus, String toStatus, String decision, String cause,
                        String rawStatus, String detail, Instant createdAt) {
    }

    public void record(UUID app, String type, String id, Enum<?> from, Enum<?> to, Decision decision,
                       Cause cause, String rawStatus, String detail) {
        jdbc.sql("""
                        INSERT INTO status_events
                            (application_id, resource_type, resource_id, from_status, to_status, decision, cause, raw_status, detail)
                        VALUES (:app, :type, :id, :from, :to, :decision, :cause, :raw, :detail)""")
                .param("app", app)
                .param("type", type)
                .param("id", id)
                .param("from", from == null ? null : from.name())
                .param("to", to.name())
                .param("decision", decision.name())
                .param("cause", cause.name())
                .param("raw", rawStatus)
                .param("detail", detail)
                .update();
    }

    public List<Event> history(UUID app, String type, String id) {
        return jdbc.sql("""
                        SELECT from_status, to_status, decision, cause, raw_status, detail, created_at
                        FROM status_events
                        WHERE application_id = :app AND resource_type = :type AND resource_id = :id
                        ORDER BY id""")
                .param("app", app).param("type", type).param("id", id)
                .query(Event.class)
                .list();
    }
}
