package dev.yoonpay.server.refund;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

@Repository
public class RefundRepository {

    private final JdbcClient jdbc;

    public RefundRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(RefundRecord r) {
        jdbc.sql("""
                        INSERT INTO refunds (id, application_id, payment_id, status, amount, currency, reason, provider)
                        VALUES (:id, :app, :payment, :status, :amount, :currency, :reason, :provider)""")
                .param("id", r.id()).param("app", r.applicationId()).param("payment", r.paymentId())
                .param("status", r.status()).param("amount", r.amount()).param("currency", r.currency())
                .param("reason", r.reason()).param("provider", r.provider())
                .update();
    }

    /** Refunds that are, or may become, money returned: everything but FAILED. */
    public long committedAmount(String paymentId) {
        return jdbc.sql("SELECT coalesce(sum(amount), 0) FROM refunds WHERE payment_id = :p AND status <> 'FAILED'")
                .param("p", paymentId).query(Long.class).single();
    }

    public Optional<RefundRecord> find(UUID app, String id) {
        return jdbc.sql("SELECT * FROM refunds WHERE application_id = :app AND id = :id")
                .param("app", app).param("id", id).query(RefundRecord.class).optional();
    }

    public Optional<RefundRecord> lock(UUID app, String id) {
        return jdbc.sql("SELECT * FROM refunds WHERE application_id = :app AND id = :id FOR UPDATE")
                .param("app", app).param("id", id).query(RefundRecord.class).optional();
    }

    public void update(String id, String status, Map<String, Object> fields) {
        StringBuilder sql = new StringBuilder("UPDATE refunds SET status = :status, updated_at = now()");
        fields.keySet().forEach(k -> sql.append(", ").append(k).append(" = :").append(k));
        var spec = jdbc.sql(sql + " WHERE id = :id").param("id", id).param("status", status);
        for (var e : fields.entrySet()) {
            spec = spec.param(e.getKey(), e.getValue());
        }
        spec.update();
    }

    public List<RefundRecord> forPayment(UUID app, String paymentId) {
        return jdbc.sql("SELECT * FROM refunds WHERE application_id = :app AND payment_id = :p ORDER BY id")
                .param("app", app).param("p", paymentId).query(RefundRecord.class).list();
    }

    public List<RefundRecord> list(UUID app, String status, String startingAfter, int limit) {
        return jdbc.sql("""
                        SELECT * FROM refunds
                        WHERE application_id = :app
                          AND (CAST(:status AS TEXT) IS NULL OR status = :status)
                          AND (CAST(:cursor AS TEXT) IS NULL OR id < :cursor)
                        ORDER BY id DESC LIMIT :limit""")
                .param("app", app).param("status", status).param("cursor", startingAfter).param("limit", limit + 1)
                .query(RefundRecord.class).list();
    }

    public Stream<RefundRecord> stream(UUID app, Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT * FROM refunds
                        WHERE application_id = :app
                          AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR created_at >= :from)
                          AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR created_at < :to)
                        ORDER BY id""")
                .param("app", app)
                .param("from", from == null ? null : java.sql.Timestamp.from(from))
                .param("to", to == null ? null : java.sql.Timestamp.from(to))
                .query(RefundRecord.class).stream();
    }
}
