package dev.yoonpay.server.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class Applications {

    private final JdbcClient jdbc;

    public Applications(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AppPrincipal> find(UUID id) {
        return jdbc.sql("SELECT id, name FROM applications WHERE id = :id")
                .param("id", id).query(AppPrincipal.class).optional();
    }
}
