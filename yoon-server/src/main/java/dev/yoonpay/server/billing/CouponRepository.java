package dev.yoonpay.server.billing;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Coupons (ADR-0029). Terms are immutable (database trigger); nothing is deleted. */
@Repository
public class CouponRepository {

    private static final String COUPON = """
            SELECT id, application_id, code, percent_off, amount_off, currency, duration, duration_in_periods,
                   max_redemptions, times_redeemed, redeem_by, array_to_string(plan_ids, ',') AS plan_ids, active,
                   metadata::text AS metadata, created_at, updated_at
            FROM coupons""";

    private final JdbcClient jdbc;

    public CouponRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(CouponRecord c) {
        jdbc.sql("""
                        INSERT INTO coupons (id, application_id, code, percent_off, amount_off, currency, duration,
                                             duration_in_periods, max_redemptions, redeem_by, plan_ids, metadata)
                        VALUES (:id, :app, :code, :percent, :amount, :currency, :duration, :periods, :max, :redeemBy,
                                string_to_array(CAST(:plans AS TEXT), ','), CAST(:metadata AS jsonb))""")
                .param("id", c.id()).param("app", c.applicationId()).param("code", c.code())
                .param("percent", c.percentOff()).param("amount", c.amountOff()).param("currency", c.currency())
                .param("duration", c.duration()).param("periods", c.durationInPeriods())
                .param("max", c.maxRedemptions())
                .param("redeemBy", c.redeemBy() == null ? null : Timestamp.from(c.redeemBy()))
                .param("plans", c.planIds()).param("metadata", c.metadata())
                .update();
    }

    public void update(String id, boolean active, Integer maxRedemptions, String metadata) {
        jdbc.sql("""
                        UPDATE coupons SET active = :active, max_redemptions = :max, metadata = CAST(:metadata AS jsonb),
                               updated_at = now()
                        WHERE id = :id""")
                .param("id", id).param("active", active).param("max", maxRedemptions).param("metadata", metadata)
                .update();
    }

    public Optional<CouponRecord> find(UUID app, String id) {
        return jdbc.sql(COUPON + " WHERE application_id = :app AND id = :id")
                .param("app", app).param("id", id).query(CouponRecord.class).optional();
    }

    /** Case-insensitive, as customers type codes. */
    public Optional<CouponRecord> findByCode(UUID app, String code) {
        return jdbc.sql(COUPON + " WHERE application_id = :app AND upper(code) = upper(:code)")
                .param("app", app).param("code", code).query(CouponRecord.class).optional();
    }

    public Optional<CouponRecord> lock(UUID app, String id) {
        return jdbc.sql(COUPON + " WHERE application_id = :app AND id = :id FOR UPDATE")
                .param("app", app).param("id", id).query(CouponRecord.class).optional();
    }

    public List<CouponRecord> list(UUID app, String code, Boolean active, String startingAfter, int limit) {
        return jdbc.sql(COUPON + """
                         WHERE application_id = :app
                          AND (CAST(:code AS TEXT) IS NULL OR upper(code) = upper(:code))
                          AND (CAST(:active AS BOOLEAN) IS NULL OR active = :active)
                          AND (CAST(:cursor AS TEXT) IS NULL OR id < :cursor)
                        ORDER BY id DESC LIMIT :limit""")
                .param("app", app).param("code", code).param("active", active).param("cursor", startingAfter)
                .param("limit", limit + 1)
                .query(CouponRecord.class).list();
    }

    /** One more use, counted on the locked coupon row (the cap check constraint backs it up). */
    public void recordRedemption(CouponRecord c, String subscriptionId, String customerId) {
        jdbc.sql("UPDATE coupons SET times_redeemed = times_redeemed + 1, updated_at = now() WHERE id = :id")
                .param("id", c.id()).update();
        jdbc.sql("""
                        INSERT INTO coupon_redemptions (application_id, coupon_id, subscription_id, customer_id)
                        VALUES (:app, :coupon, :subscription, :customer)""")
                .param("app", c.applicationId()).param("coupon", c.id()).param("subscription", subscriptionId)
                .param("customer", customerId).update();
    }
}
