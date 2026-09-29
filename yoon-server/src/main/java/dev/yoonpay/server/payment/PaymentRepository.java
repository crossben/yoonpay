package dev.yoonpay.server.payment;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

@Repository
public class PaymentRepository {

    static final String SELECT = """
            SELECT p.*,
                   coalesce((SELECT sum(r.amount) FROM refunds r
                             WHERE r.payment_id = p.id AND r.status = 'REFUNDED'), 0) AS amount_refunded
            FROM payments p""";

    private final JdbcClient jdbc;

    public PaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(PaymentRecord p) {
        jdbc.sql("""
                        INSERT INTO payments (id, application_id, status, amount, currency, country, method, reference,
                                              description, customer_phone, return_url)
                        VALUES (:id, :app, :status, :amount, :currency, :country, :method, :reference,
                                :description, :phone, :returnUrl)""")
                .param("id", p.id())
                .param("app", p.applicationId())
                .param("status", p.status())
                .param("amount", p.amount())
                .param("currency", p.currency())
                .param("country", p.country())
                .param("method", p.method())
                .param("reference", p.reference())
                .param("description", p.description())
                .param("phone", p.customerPhone())
                .param("returnUrl", p.returnUrl())
                .update();
    }

    public Optional<PaymentRecord> find(UUID app, String id) {
        return jdbc.sql(SELECT + " WHERE p.application_id = :app AND p.id = :id")
                .param("app", app).param("id", id)
                .query(PaymentRecord.class).optional();
    }

    /** Locks the row for the rest of the transaction: the one way to change a payment. */
    public Optional<PaymentRecord> lock(UUID app, String id) {
        return jdbc.sql("""
                        SELECT p.*, 0 AS amount_refunded FROM payments p
                        WHERE p.application_id = :app AND p.id = :id FOR UPDATE""")
                .param("app", app).param("id", id)
                .query(PaymentRecord.class).optional();
    }

    public void update(String id, String status, Map<String, Object> fields) {
        StringBuilder sql = new StringBuilder("UPDATE payments SET status = :status, updated_at = now()");
        fields.keySet().forEach(k -> sql.append(", ").append(k).append(" = :").append(k));
        var spec = jdbc.sql(sql + " WHERE id = :id").param("id", id).param("status", status);
        for (var e : fields.entrySet()) {
            spec = spec.param(e.getKey(), e.getValue());
        }
        spec.update();
    }

    /** Records a reference found after the fact (lost answer recovered through the provider's lookup). */
    public void setProviderReference(String id, String providerReference) {
        jdbc.sql("UPDATE payments SET provider_reference = :ref WHERE id = :id AND provider_reference IS NULL")
                .param("id", id).param("ref", providerReference).update();
    }

    public void countStatusCheck(String id) {
        jdbc.sql("UPDATE payments SET status_checks = status_checks + 1 WHERE id = :id").param("id", id).update();
    }

    public void markLateCheckDone(String id) {
        jdbc.sql("UPDATE payments SET late_check_done = true WHERE id = :id").param("id", id).update();
    }

    /** Attempts whose outcome was never seen (unknown, or interrupted before the answer was stored), newest first. */
    public List<String> unresolvedAttempts(String paymentId) {
        return jdbc.sql("""
                        SELECT id FROM payment_attempts
                        WHERE payment_id = :p AND (outcome = 'UNKNOWN' OR outcome IS NULL)
                        ORDER BY id DESC""")
                .param("p", paymentId).query(String.class).list();
    }

    public boolean hasAttempts(String paymentId) {
        return jdbc.sql("SELECT count(*) FROM payment_attempts WHERE payment_id = :p")
                .param("p", paymentId).query(Integer.class).single() > 0;
    }

    public void insertAttempt(String attemptId, String paymentId, String provider) {
        jdbc.sql("INSERT INTO payment_attempts (id, payment_id, provider) VALUES (:id, :payment, :provider)")
                .param("id", attemptId).param("payment", paymentId).param("provider", provider).update();
    }

    public void completeAttempt(String attemptId, String outcome, String providerReference, String code, String message) {
        jdbc.sql("""
                        UPDATE payment_attempts
                        SET outcome = :outcome, provider_reference = :ref, outcome_code = :code,
                            outcome_message = :message, completed_at = now()
                        WHERE id = :id""")
                .param("id", attemptId).param("outcome", outcome).param("ref", providerReference)
                .param("code", code).param("message", message).update();
    }

    public record Filter(String reference, String status, String provider, String method,
                         Instant createdFrom, Instant createdTo, String customerPhone) {
    }

    /** Newest first, keyset-paginated on the time-ordered id. Fetches {@code limit + 1} rows. */
    public List<PaymentRecord> list(UUID app, Filter f, String startingAfter, int limit) {
        Map<String, Object> params = new HashMap<>();
        String where = where(app, f, params);
        if (startingAfter != null) {
            where += " AND p.id < :cursor";
            params.put("cursor", startingAfter);
        }
        return jdbc.sql(SELECT + where + " ORDER BY p.id DESC LIMIT :limit")
                .params(params).param("limit", limit + 1)
                .query(PaymentRecord.class).list();
    }

    /** Oldest first, for exports. The caller must close the stream. */
    public Stream<PaymentRecord> stream(UUID app, Instant from, Instant to) {
        Map<String, Object> params = new HashMap<>();
        String where = where(app, new Filter(null, null, null, null, from, to, null), params);
        return jdbc.sql(SELECT + where + " ORDER BY p.id").params(params).query(PaymentRecord.class).stream();
    }

    private static String where(UUID app, Filter f, Map<String, Object> params) {
        List<String> clauses = new ArrayList<>(List.of("p.application_id = :app"));
        params.put("app", app);
        add(clauses, params, "p.reference = :reference", "reference", f.reference());
        add(clauses, params, "p.status = :status", "status", f.status());
        add(clauses, params, "p.provider = :provider", "provider", f.provider());
        add(clauses, params, "p.method = :method", "method", f.method());
        add(clauses, params, "p.created_at >= :from", "from", f.createdFrom() == null ? null : java.sql.Timestamp.from(f.createdFrom()));
        add(clauses, params, "p.created_at < :to", "to", f.createdTo() == null ? null : java.sql.Timestamp.from(f.createdTo()));
        add(clauses, params, "p.customer_phone = :phone", "phone", f.customerPhone());
        return " WHERE " + String.join(" AND ", clauses);
    }

    static void add(List<String> clauses, Map<String, Object> params, String clause, String name, Object value) {
        if (value != null) {
            clauses.add(clause);
            params.put(name, value);
        }
    }
}
