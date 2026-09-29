package dev.yoonpay.server;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

/**
 * Base for integration tests: one real Postgres and one running server for the whole run
 * (shared through Spring's context cache), schema applied by Flyway, FakeProviders wired in.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "yoon.apps.shop.providers.fakeone.priority=1",
        "yoon.apps.shop.providers.faketwo.priority=2",
        "yoon.apps.shop.providers.fakenorefund.priority=3",
        "yoon.apps.other.providers.fakeone.priority=1",
})
@Import(TestProviders.class)
public abstract class PostgresTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
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
