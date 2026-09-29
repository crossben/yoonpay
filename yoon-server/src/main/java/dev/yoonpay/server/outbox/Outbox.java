package dev.yoonpay.server.outbox;

import dev.yoonpay.core.id.Ids;
import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.config.YoonProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes an outbound event in the caller's transaction, so the event exists if and only if
 * the state change committed. {@link OutboxDelivery} sends it later.
 */
@Component
public class Outbox {

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final YoonProperties properties;
    private final Clock clock;

    public Outbox(JdbcClient jdbc, ObjectMapper json, YoonProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.properties = properties;
        this.clock = clock;
    }

    /** @param data the resource as the API returns it */
    @Transactional(propagation = Propagation.MANDATORY)
    public String emit(AppPrincipal app, String type, String resourceType, String resourceId, Object data) {
        String id = Ids.next("evt");
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", id);
        event.put("object", "event");
        event.put("type", type);
        event.put("created_at", Instant.now(clock).toString());
        event.put("data", Map.of("object", data));

        boolean hasEndpoint = properties.app(app.name()).webhook() != null
                && properties.app(app.name()).webhook().url() != null;
        jdbc.sql("""
                        INSERT INTO outbound_events (id, application_id, type, resource_type, resource_id, payload, delivery_status)
                        VALUES (:id, :app, :type, :rtype, :rid, :payload, :status)""")
                .param("id", id).param("app", app.id()).param("type", type)
                .param("rtype", resourceType).param("rid", resourceId)
                .param("payload", json.writeValueAsString(event))
                .param("status", hasEndpoint ? "PENDING" : "NO_ENDPOINT")
                .update();
        return id;
    }
}
