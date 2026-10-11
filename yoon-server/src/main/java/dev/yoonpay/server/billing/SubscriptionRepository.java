package dev.yoonpay.server.billing;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SubscriptionRepository {

    private static final String SELECT = """
            SELECT id, application_id, customer_id, price_id, status, collection_method, billing_anchor,
                   current_period_start, current_period_end, grace_period_days, cancel_at_period_end,
                   pending_price_id, canceled_at, comp, reminded_period_end, metadata::text AS metadata,
                   created_at, updated_at
            FROM subscriptions""";

    private final JdbcClient jdbc;

    public SubscriptionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(SubscriptionRecord s) {
        jdbc.sql("""
                        INSERT INTO subscriptions (id, application_id, customer_id, price_id, status, grace_period_days, metadata)
                        VALUES (:id, :app, :customer, :price, :status, :grace, CAST(:metadata AS jsonb))""")
                .param("id", s.id()).param("app", s.applicationId()).param("customer", s.customerId())
                .param("price", s.priceId()).param("status", s.status()).param("grace", s.gracePeriodDays())
                .param("metadata", s.metadata())
                .update();
    }

    public Optional<SubscriptionRecord> find(UUID app, String id) {
        return jdbc.sql(SELECT + " WHERE application_id = :app AND id = :id")
                .param("app", app).param("id", id).query(SubscriptionRecord.class).optional();
    }

    /** Locks the row for the rest of the transaction: the one way to change a subscription. */
    public Optional<SubscriptionRecord> lock(UUID app, String id) {
        return jdbc.sql(SELECT + " WHERE application_id = :app AND id = :id FOR UPDATE")
                .param("app", app).param("id", id).query(SubscriptionRecord.class).optional();
    }

    /** Sets columns by name; callers pass only known column names. */
    public void update(String id, Map<String, Object> fields) {
        StringBuilder sql = new StringBuilder("UPDATE subscriptions SET updated_at = now()");
        fields.keySet().forEach(k -> sql.append(", ").append(k).append(" = :").append(k));
        var spec = jdbc.sql(sql + " WHERE id = :id").param("id", id);
        for (var e : fields.entrySet()) {
            spec = spec.param(e.getKey(), e.getValue() instanceof java.time.Instant i ? java.sql.Timestamp.from(i) : e.getValue());
        }
        spec.update();
    }

    public List<SubscriptionRecord> list(UUID app, String customerId, String status, String startingAfter, int limit) {
        return jdbc.sql(SELECT + """
                         WHERE application_id = :app
                          AND (CAST(:customer AS TEXT) IS NULL OR customer_id = :customer)
                          AND (CAST(:status AS TEXT) IS NULL OR status = :status)
                          AND (CAST(:cursor AS TEXT) IS NULL OR id < :cursor)
                        ORDER BY id DESC LIMIT :limit""")
                .param("app", app).param("customer", customerId).param("status", status)
                .param("cursor", startingAfter).param("limit", limit + 1)
                .query(SubscriptionRecord.class).list();
    }
}
