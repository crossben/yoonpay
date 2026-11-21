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

    /**
     * Refunds that are, or may become, money returned. A FAILED refund the provider never saw
     * (no reference) is free again; one the provider saw stays committed — it may still be
     * confirmed late — until an operator releases it (decision D1, money-safety plan 2026-11).
     */
    public long committedAmount(String paymentId) {
        return jdbc.sql("""
                        SELECT coalesce(sum(amount), 0) FROM refunds
                        WHERE payment_id = :p
                          AND (status <> 'FAILED' OR (provider_reference IS NOT NULL AND amount_released_at IS NULL))""")
                .param("p", paymentId).query(Long.class).single();
    }

    /** Money actually returned on a payment: REFUNDED refunds only. */
    public long refundedAmount(String paymentId) {
        return jdbc.sql("SELECT coalesce(sum(amount), 0) FROM refunds WHERE payment_id = :p AND status = 'REFUNDED'")
                .param("p", paymentId).query(Long.class).single();
    }

    public void releaseAmount(String id) {
        jdbc.sql("UPDATE refunds SET amount_released_at = now(), updated_at = now() WHERE id = :id").param("id", id).update();
    }

    public Optional<RefundRecord> find(UUID app, String id) {
        return jdbc.sql("SELECT * FROM refunds WHERE application_id = :app AND id = :id")
                .param("app", app).param("id", id).query(RefundRecord.class).optional();
    }

    /** Any application's refund: for operator actions, which name a refund by id only. */
    public Optional<RefundRecord> findAnyApplication(String id) {
        return jdbc.sql("SELECT * FROM refunds WHERE id = :id").param("id", id).query(RefundRecord.class).optional();
    }

    public Optional<RefundRecord> lock(UUID app, String id) {
        return jdbc.sql("SELECT * FROM refunds WHERE application_id = :app AND id = :id FOR UPDATE")
                .param("app", app).param("id", id).query(RefundRecord.class).optional();
    }

    public void setProviderReference(String id, String providerReference) {
        jdbc.sql("UPDATE refunds SET provider_reference = :ref WHERE id = :id AND provider_reference IS NULL")
                .param("id", id).param("ref", providerReference).update();
    }

    public void countStatusCheck(String id) {
        jdbc.sql("UPDATE refunds SET status_checks = status_checks + 1 WHERE id = :id").param("id", id).update();
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
