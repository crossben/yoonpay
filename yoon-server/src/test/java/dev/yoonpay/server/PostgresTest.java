package dev.yoonpay.server;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

/**
 * Base for integration tests: one real Postgres for the whole run (started once, shared by
 * every test class through Spring's context cache), schema applied by Flyway.
 */
@SpringBootTest
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
