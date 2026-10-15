package dev.yoonpay.server.portal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

/** Customer portal sessions (ADR-0031). */
@Repository
public class PortalSessionRepository {

    private static final String SELECT = """
            SELECT id, application_id, customer_id, token_hash, allow_cancel,
                   array_to_string(change_prices, ',') AS change_prices, allow_coupons, return_url, expires_at,
                   created_at
            FROM portal_sessions""";

    private final JdbcClient jdbc;

    public PortalSessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(PortalSessionRecord s) {
        jdbc.sql("""
                        INSERT INTO portal_sessions (id, application_id, customer_id, token_hash, allow_cancel,
                                                     change_prices, allow_coupons, return_url, expires_at)
                        VALUES (:id, :app, :customer, :hash, :cancel, string_to_array(CAST(:prices AS TEXT), ','),
                                :coupons, :returnUrl, :expiresAt)""")
                .param("id", s.id()).param("app", s.applicationId()).param("customer", s.customerId())
                .param("hash", s.tokenHash()).param("cancel", s.allowCancel()).param("prices", s.changePrices())
                .param("coupons", s.allowCoupons()).param("returnUrl", s.returnUrl())
                .param("expiresAt", Timestamp.from(s.expiresAt()))
                .update();
    }

    /** Unscoped: only for the public portal page, which then checks the token and the expiry. */
    public Optional<PortalSessionRecord> find(String id) {
        return jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query(PortalSessionRecord.class).optional();
    }
}
