package dev.yoonpay.server.outbox;

import dev.yoonpay.server.auth.Applications;
import dev.yoonpay.server.config.YoonProperties;
import dev.yoonpay.server.lifecycle.Alerts;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Delivers outbound events to each application's webhook URL. Rows are claimed with a lease
 * ({@code SKIP LOCKED}) so nodes never deliver the same row concurrently. 2xx = delivered;
 * anything else is retried with exponential backoff (30 s, 1 min, 2 min … capped at 6 h) and
 * becomes DEAD after {@link #MAX_ATTEMPTS}, visible and replayable through the admin API.
 */
@Component
public class OutboxDelivery {

    public static final int MAX_ATTEMPTS = 12;
    private static final Duration FIRST_RETRY = Duration.ofSeconds(30);
    private static final Duration MAX_RETRY = Duration.ofHours(6);

    private final JdbcClient jdbc;
    private final Applications applications;
    private final YoonProperties properties;
    private final Alerts alerts;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public OutboxDelivery(JdbcClient jdbc, Applications applications, YoonProperties properties, Alerts alerts, Clock clock) {
        this.jdbc = jdbc;
        this.applications = applications;
        this.properties = properties;
        this.alerts = alerts;
        this.clock = clock;
    }

    record Due(String id, UUID applicationId, String type, String payload, int attempts) {
    }

    /** Delivers up to {@code batch} due events; returns how many were attempted. */
    public int deliverDue(int batch) {
        List<Due> due = jdbc.sql("""
                        UPDATE outbound_events SET locked_until = now() + interval '2 minutes'
                        WHERE id IN (SELECT id FROM outbound_events
                                     WHERE delivery_status = 'PENDING' AND next_attempt_at <= now()
                                       AND (locked_until IS NULL OR locked_until < now())
                                     ORDER BY next_attempt_at, id LIMIT :batch FOR UPDATE SKIP LOCKED)
                        RETURNING id, application_id, type, payload, attempts""")
                .param("batch", batch).query(Due.class).list();
        due.forEach(this::deliver);
        return due.size();
    }

    private void deliver(Due e) {
        var webhook = applications.find(e.applicationId())
                .map(a -> properties.app(a.name()).webhook())
                .filter(w -> w != null && w.url() != null);
        if (webhook.isEmpty()) {
            jdbc.sql("UPDATE outbound_events SET delivery_status = 'NO_ENDPOINT', locked_until = NULL WHERE id = :id")
                    .param("id", e.id()).update();
            return;
        }

        long now = clock.instant().getEpochSecond();
        Integer status = null;
        String error = null;
        try {
            HttpRequest request = HttpRequest.newBuilder(webhook.get().url())
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Yoon-Webhooks/1")
                    .header("Yoon-Event-Id", e.id())
                    .header("Yoon-Event-Type", e.type())
                    .header(WebhookSignature.HEADER, WebhookSignature.sign(webhook.get().secret(), now, e.payload()))
                    .POST(HttpRequest.BodyPublishers.ofString(e.payload()))
                    .build();
            status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            error = "interrupted";
        } catch (Exception ex) {
            error = ex.getClass().getSimpleName();
        }

        if (status != null && status >= 200 && status < 300) {
            jdbc.sql("""
                            UPDATE outbound_events
                            SET delivery_status = 'DELIVERED', delivered_at = now(), attempts = attempts + 1,
                                last_status_code = :code, last_error = NULL, locked_until = NULL
                            WHERE id = :id""")
                    .param("id", e.id()).param("code", status).update();
            return;
        }

        int attempts = e.attempts() + 1;
        boolean dead = attempts >= MAX_ATTEMPTS;
        String reason = error != null ? error : "HTTP " + status;
        jdbc.sql("""
                        UPDATE outbound_events
                        SET attempts = :attempts, last_status_code = :code, last_error = :error, locked_until = NULL,
                            delivery_status = :status, next_attempt_at = now() + make_interval(secs => :delay)
                        WHERE id = :id""")
                .param("id", e.id()).param("attempts", attempts).param("code", status).param("error", reason)
                .param("status", dead ? "DEAD" : "PENDING").param("delay", backoff(attempts).toSeconds())
                .update();
        if (dead) {
            alerts.raise(Alerts.Type.event_dead, e.id(), "after " + attempts + " attempts, last: " + reason);
        }
    }

    public static Duration backoff(int attempts) {
        Duration d = FIRST_RETRY.multipliedBy(1L << Math.min(attempts - 1, 20));
        return d.compareTo(MAX_RETRY) > 0 ? MAX_RETRY : d;
    }
}
