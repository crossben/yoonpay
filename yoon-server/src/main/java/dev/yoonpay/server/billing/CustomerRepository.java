package dev.yoonpay.server.billing;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CustomerRepository {

    private static final String SELECT = """
            SELECT id, application_id, external_id, email, phone, pi_alias, country, name,
                   metadata::text AS metadata, created_at, updated_at
            FROM customers""";

    private final JdbcClient jdbc;

    public CustomerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(CustomerRecord c) {
        jdbc.sql("""
                        INSERT INTO customers (id, application_id, external_id, email, phone, pi_alias, country, name, metadata)
                        VALUES (:id, :app, :external, :email, :phone, :alias, :country, :name, CAST(:metadata AS jsonb))""")
                .param("id", c.id()).param("app", c.applicationId()).param("external", c.externalId())
                .param("email", c.email()).param("phone", c.phone()).param("alias", c.piAlias())
                .param("country", c.country()).param("name", c.name()).param("metadata", c.metadata())
                .update();
    }

    public void update(CustomerRecord c) {
        jdbc.sql("""
                        UPDATE customers SET email = :email, phone = :phone, pi_alias = :alias, country = :country,
                               name = :name, metadata = CAST(:metadata AS jsonb), updated_at = now()
                        WHERE application_id = :app AND id = :id""")
                .param("id", c.id()).param("app", c.applicationId())
                .param("email", c.email()).param("phone", c.phone()).param("alias", c.piAlias())
                .param("country", c.country()).param("name", c.name()).param("metadata", c.metadata())
                .update();
    }

    public Optional<CustomerRecord> find(UUID app, String id) {
        return jdbc.sql(SELECT + " WHERE application_id = :app AND id = :id")
                .param("app", app).param("id", id).query(CustomerRecord.class).optional();
    }

    public Optional<CustomerRecord> findByExternalId(UUID app, String externalId) {
        return jdbc.sql(SELECT + " WHERE application_id = :app AND external_id = :external")
                .param("app", app).param("external", externalId).query(CustomerRecord.class).optional();
    }

    public List<CustomerRecord> list(UUID app, String externalId, String startingAfter, int limit) {
        return jdbc.sql(SELECT + """
                         WHERE application_id = :app
                          AND (CAST(:external AS TEXT) IS NULL OR external_id = :external)
                          AND (CAST(:cursor AS TEXT) IS NULL OR id < :cursor)
                        ORDER BY id DESC LIMIT :limit""")
                .param("app", app).param("external", externalId).param("cursor", startingAfter)
                .param("limit", limit + 1).query(CustomerRecord.class).list();
    }
}
