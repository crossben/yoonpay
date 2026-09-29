package dev.yoonpay.server;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

/**
 * Base for integration tests: one real Postgres and one running server for the whole run
 * (shared through Spring's context cache), schema applied by Flyway, FakeProviders wired in.
 * Background scheduling is off: tests run the workers and sweeps themselves.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "yoon.scheduling.enabled=false",
        "yoon.admin-token=" + PostgresTest.ADMIN_TOKEN,
        "yoon.apps.shop.providers.fakeone.priority=1",
        "yoon.apps.shop.providers.faketwo.priority=2",
        "yoon.apps.shop.providers.fakenorefund.priority=3",
        "yoon.apps.shop.webhook.secret=" + TestWebhookReceiver.SECRET,
        "yoon.apps.other.providers.fakeone.priority=1",
        // "live" talks to the real DexPay adapter, against WireMock.
        "yoon.apps.live.providers.dexpay.priority=1",
        "yoon.apps.live.providers.dexpay.credentials.api-key=pk_test",
        "yoon.apps.live.providers.dexpay.credentials.api-secret=sk_test",
        "yoon.apps.live.providers.dexpay.credentials.webhook-secret=" + PostgresTest.DEXPAY_WEBHOOK_SECRET,
        "yoon.public-url=https://yoon.example",
})
@Import(TestProviders.class)
public abstract class PostgresTest {

    public static final String ADMIN_TOKEN = "test-admin-token-0123456789abcdefghij";
    public static final String DEXPAY_WEBHOOK_SECRET = "dexpay-dashboard-webhook-secret";

    /** Stands in for DexPay's API for the "live" application. */
    public static final com.github.tomakehurst.wiremock.WireMockServer DEXPAY =
            new com.github.tomakehurst.wiremock.WireMockServer(
                    com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig().dynamicPort());

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    /** The "shop" application's webhook endpoint. */
    public static final TestWebhookReceiver RECEIVER = new TestWebhookReceiver();

    static {
        POSTGRES.start();
        DEXPAY.start();
    }

    @DynamicPropertySource
    static void webhookUrl(DynamicPropertyRegistry registry) {
        registry.add("yoon.apps.shop.webhook.url", RECEIVER::url);
        registry.add("yoon.apps.live.providers.dexpay.credentials.base-url", () -> DEXPAY.baseUrl() + "/api/v1");
    }

    @Autowired
    protected JdbcClient jdbc;

    protected UUID newApplication() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO applications (id, name) VALUES (:id, :name)")
                .param("id", id)
                .param("name", "app-" + id)
                .update();
        return id;
    }
}
