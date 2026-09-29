package dev.yoonpay.server.payout;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

@Repository
public class PayoutRepository {

    private final JdbcClient jdbc;

    public PayoutRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(PayoutRecord p) {
        jdbc.sql("""
                        INSERT INTO payouts (id, application_id, status, amount, currency, country, method, reference,
                                             recipient_phone, provider, routing_reason)
                        VALUES (:id, :app, :status, :amount, :currency, :country, :method, :reference,
                                :phone, :provider, :routing)""")
                .param("id", p.id()).param("app", p.applicationId()).param("status", p.status())
                .param("amount", p.amount()).param("currency", p.currency()).param("country", p.country())
                .param("method", p.method()).param("reference", p.reference()).param("phone", p.recipientPhone())
                .param("provider", p.provider()).param("routing", p.routingReason())
                .update();
    }

    public Optional<PayoutRecord> find(UUID app, String id) {
        return jdbc.sql("SELECT * FROM payouts WHERE application_id = :app AND id = :id")
                .param("app", app).param("id", id).query(PayoutRecord.class).optional();
    }

    public Optional<PayoutRecord> lock(UUID app, String id) {
        return jdbc.sql("SELECT * FROM payouts WHERE application_id = :app AND id = :id FOR UPDATE")
                .param("app", app).param("id", id).query(PayoutRecord.class).optional();
    }

    public void setProviderReference(String id, String providerReference) {
        jdbc.sql("UPDATE payouts SET provider_reference = :ref WHERE id = :id AND provider_reference IS NULL")
                .param("id", id).param("ref", providerReference).update();
    }

    public void countStatusCheck(String id) {
        jdbc.sql("UPDATE payouts SET status_checks = status_checks + 1 WHERE id = :id").param("id", id).update();
    }

    public void update(String id, String status, Map<String, Object> fields) {
        StringBuilder sql = new StringBuilder("UPDATE payouts SET status = :status, updated_at = now()");
        fields.keySet().forEach(k -> sql.append(", ").append(k).append(" = :").append(k));
        var spec = jdbc.sql(sql + " WHERE id = :id").param("id", id).param("status", status);
        for (var e : fields.entrySet()) {
            spec = spec.param(e.getKey(), e.getValue());
        }
        spec.update();
    }

    public List<PayoutRecord> list(UUID app, String status, String reference, Boolean needsReview,
                                   String startingAfter, int limit) {
        return jdbc.sql("""
                        SELECT * FROM payouts
                        WHERE application_id = :app
                          AND (CAST(:status AS TEXT) IS NULL OR status = :status)
                          AND (CAST(:reference AS TEXT) IS NULL OR reference = :reference)
                          AND (CAST(:review AS BOOLEAN) IS NULL OR needs_review = :review)
                          AND (CAST(:cursor AS TEXT) IS NULL OR id < :cursor)
                        ORDER BY id DESC LIMIT :limit""")
                .param("app", app).param("status", status).param("reference", reference)
                .param("review", needsReview).param("cursor", startingAfter).param("limit", limit + 1)
                .query(PayoutRecord.class).list();
    }

    public Stream<PayoutRecord> stream(UUID app, Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT * FROM payouts
                        WHERE application_id = :app
                          AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR created_at >= :from)
                          AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR created_at < :to)
                        ORDER BY id""")
                .param("app", app)
                .param("from", from == null ? null : java.sql.Timestamp.from(from))
                .param("to", to == null ? null : java.sql.Timestamp.from(to))
                .query(PayoutRecord.class).stream();
    }
}
