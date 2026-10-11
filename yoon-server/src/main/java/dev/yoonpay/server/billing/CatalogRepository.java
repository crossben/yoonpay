package dev.yoonpay.server.billing;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Plans and prices. Nothing here deletes: archiving is {@code active = false}. */
@Repository
public class CatalogRepository {

    private static final String PLAN = """
            SELECT id, application_id, slug, name, active, features::text AS features, metadata::text AS metadata,
                   created_at, updated_at
            FROM plans""";

    private final JdbcClient jdbc;

    public CatalogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertPlan(PlanRecord p) {
        jdbc.sql("""
                        INSERT INTO plans (id, application_id, slug, name, active, features, metadata)
                        VALUES (:id, :app, :slug, :name, true, CAST(:features AS jsonb), CAST(:metadata AS jsonb))""")
                .param("id", p.id()).param("app", p.applicationId()).param("slug", p.slug()).param("name", p.name())
                .param("features", p.features()).param("metadata", p.metadata())
                .update();
    }

    public void updatePlan(PlanRecord p) {
        jdbc.sql("""
                        UPDATE plans SET name = :name, active = :active, features = CAST(:features AS jsonb),
                               metadata = CAST(:metadata AS jsonb), updated_at = now()
                        WHERE application_id = :app AND id = :id""")
                .param("id", p.id()).param("app", p.applicationId()).param("name", p.name()).param("active", p.active())
                .param("features", p.features()).param("metadata", p.metadata())
                .update();
    }

    public Optional<PlanRecord> findPlan(UUID app, String id) {
        return jdbc.sql(PLAN + " WHERE application_id = :app AND id = :id")
                .param("app", app).param("id", id).query(PlanRecord.class).optional();
    }

    public Optional<PlanRecord> findPlanBySlug(UUID app, String slug) {
        return jdbc.sql(PLAN + " WHERE application_id = :app AND slug = :slug")
                .param("app", app).param("slug", slug).query(PlanRecord.class).optional();
    }

    public List<PlanRecord> listPlans(UUID app, Boolean active, String startingAfter, int limit) {
        return jdbc.sql(PLAN + """
                         WHERE application_id = :app
                          AND (CAST(:active AS BOOLEAN) IS NULL OR active = :active)
                          AND (CAST(:cursor AS TEXT) IS NULL OR id < :cursor)
                        ORDER BY id DESC LIMIT :limit""")
                .param("app", app).param("active", active).param("cursor", startingAfter).param("limit", limit + 1)
                .query(PlanRecord.class).list();
    }

    public void insertPrice(PriceRecord p) {
        jdbc.sql("""
                        INSERT INTO prices (id, application_id, plan_id, currency, amount, interval, interval_count, active)
                        VALUES (:id, :app, :plan, :currency, :amount, :interval, :count, true)""")
                .param("id", p.id()).param("app", p.applicationId()).param("plan", p.planId())
                .param("currency", p.currency()).param("amount", p.amount()).param("interval", p.interval())
                .param("count", p.intervalCount())
                .update();
    }

    public void setPriceActive(UUID app, String id, boolean active) {
        jdbc.sql("UPDATE prices SET active = :active WHERE application_id = :app AND id = :id")
                .param("app", app).param("id", id).param("active", active).update();
    }

    public Optional<PriceRecord> findPrice(UUID app, String id) {
        return jdbc.sql("SELECT * FROM prices WHERE application_id = :app AND id = :id")
                .param("app", app).param("id", id).query(PriceRecord.class).optional();
    }

    /** Prices of the given plans, oldest first. */
    public List<PriceRecord> pricesOf(Collection<String> planIds) {
        if (planIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT * FROM prices WHERE plan_id IN (:plans) ORDER BY id")
                .param("plans", planIds).query(PriceRecord.class).list();
    }
}
