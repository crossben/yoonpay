package dev.yoonpay.server.webhook;

import dev.yoonpay.server.auth.AppPrincipal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/** Stores a provider callback, raw, for {@link InboundWebhookProcessor}. */
@Component
public class InboundWebhookStore {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public InboundWebhookStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** @param headers lower-cased names */
    public void store(AppPrincipal app, String provider, Map<String, List<String>> headers, byte[] body) {
        jdbc.sql("INSERT INTO inbound_webhooks (application_id, provider, headers, body) VALUES (:app, :provider, :headers, :body)")
                .param("app", app.id()).param("provider", provider)
                .param("headers", json.writeValueAsString(headers)).param("body", body)
                .update();
    }
}
