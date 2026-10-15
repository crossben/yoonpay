package dev.yoonpay.server.link;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Payment links (ADR-0030). Amounts never change (database trigger); nothing is deleted. */
@Repository
public class PaymentLinkRepository {

    /** Succeeded payments made through a link. */
    public record Stats(long paymentsCount, long amountCollected) {
    }

    private static final String SELECT = """
            SELECT id, application_id, code, amount, min_amount, max_amount, currency, country, description, method,
                   max_payments, expires_at, return_url, active, metadata::text AS metadata, created_at, updated_at
            FROM payment_links""";

    private final JdbcClient jdbc;

    public PaymentLinkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(PaymentLinkRecord l) {
        jdbc.sql("""
                        INSERT INTO payment_links (id, application_id, code, amount, min_amount, max_amount, currency,
                                                   country, description, method, max_payments, expires_at, return_url,
                                                   metadata)
                        VALUES (:id, :app, :code, :amount, :min, :max, :currency, :country, :description, :method,
                                :maxPayments, :expiresAt, :returnUrl, CAST(:metadata AS jsonb))""")
                .param("id", l.id()).param("app", l.applicationId()).param("code", l.code())
                .param("amount", l.amount()).param("min", l.minAmount()).param("max", l.maxAmount())
                .param("currency", l.currency()).param("country", l.country()).param("description", l.description())
                .param("method", l.method()).param("maxPayments", l.maxPayments())
                .param("expiresAt", l.expiresAt() == null ? null : Timestamp.from(l.expiresAt()))
                .param("returnUrl", l.returnUrl()).param("metadata", l.metadata())
                .update();
    }

    public void update(PaymentLinkRecord l) {
        jdbc.sql("""
                        UPDATE payment_links SET active = :active, description = :description, expires_at = :expiresAt,
                               max_payments = :maxPayments, metadata = CAST(:metadata AS jsonb), updated_at = now()
                        WHERE id = :id""")
                .param("id", l.id()).param("active", l.active()).param("description", l.description())
                .param("expiresAt", l.expiresAt() == null ? null : Timestamp.from(l.expiresAt()))
                .param("maxPayments", l.maxPayments()).param("metadata", l.metadata())
                .update();
    }

    public Optional<PaymentLinkRecord> find(UUID app, String id) {
        return jdbc.sql(SELECT + " WHERE application_id = :app AND id = :id")
                .param("app", app).param("id", id).query(PaymentLinkRecord.class).optional();
    }

    /** Unscoped: only for the public link page, which knows nothing but the code. */
    public Optional<PaymentLinkRecord> findByCode(String code) {
        return jdbc.sql(SELECT + " WHERE code = :code").param("code", code).query(PaymentLinkRecord.class).optional();
    }

    public List<PaymentLinkRecord> list(UUID app, Boolean active, String startingAfter, int limit) {
        return jdbc.sql(SELECT + """
                         WHERE application_id = :app
                          AND (CAST(:active AS BOOLEAN) IS NULL OR active = :active)
                          AND (CAST(:cursor AS TEXT) IS NULL OR id < :cursor)
                        ORDER BY id DESC LIMIT :limit""")
                .param("app", app).param("active", active).param("cursor", startingAfter).param("limit", limit + 1)
                .query(PaymentLinkRecord.class).list();
    }

    public Stats stats(String linkId) {
        return jdbc.sql("""
                        SELECT count(*) AS payments_count, coalesce(sum(amount), 0) AS amount_collected
                        FROM payments WHERE payment_link_id = :id AND status = 'SUCCEEDED'""")
                .param("id", linkId).query(Stats.class).single();
    }
}
