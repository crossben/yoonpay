package dev.yoonpay.server.billing;

import dev.yoonpay.core.lifecycle.SubscriptionStatus;
import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.auth.Applications;
import dev.yoonpay.server.config.YoonProperties;
import dev.yoonpay.server.lifecycle.StatusEvents.Cause;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The billing sweep (ADR-0026, "Lifecycle jobs"): reminders, period ends, grace ends and unpaid
 * first invoices. Runs on one node at a time (ShedLock, in {@code Scheduler}); each subscription
 * is re-checked under its row lock, so a payment settling at the same moment is never overwritten
 * and running the sweep twice changes nothing. Tests call the stages directly.
 */
@Component
public class BillingSweep {

    private static final Logger log = LoggerFactory.getLogger(BillingSweep.class);
    private static final int BATCH = 200;

    private final JdbcClient jdbc;
    private final SubscriptionRepository subscriptions;
    private final SubscriptionTransitions transitions;
    private final SubscriptionService service;
    private final Applications applications;
    private final YoonProperties properties;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;
    private final Clock clock;

    public BillingSweep(JdbcClient jdbc, SubscriptionRepository subscriptions, SubscriptionTransitions transitions,
                        SubscriptionService service, Applications applications, YoonProperties properties,
                        TransactionTemplate tx, MeterRegistry meters, Clock clock) {
        this.jdbc = jdbc;
        this.subscriptions = subscriptions;
        this.transitions = transitions;
        this.service = service;
        this.applications = applications;
        this.properties = properties;
        this.tx = tx;
        this.meters = meters;
        this.clock = clock;
    }

    /** Every stage, in an order where one pass is enough (a period that ends with zero grace expires too). */
    public void sweep() {
        remind();
        endPeriods();
        endGrace();
        expireIncomplete();
    }

    // ------------------------------------------------------------------ stages

    /**
     * {@code subscription.renewal_upcoming} (or {@code subscription.trial_will_end} for a trial), once
     * per period, the application's reminder days before its end.
     */
    public int remind() {
        Instant now = clock.instant();
        List<Row> rows = candidates("""
                SELECT id, application_id FROM subscriptions
                WHERE status IN ('ACTIVE', 'TRIALING') AND cancel_at_period_end = false AND current_period_end IS NOT NULL
                  AND current_period_end > :now AND current_period_end <= :horizon
                  AND reminded_period_end IS DISTINCT FROM current_period_end
                ORDER BY current_period_end LIMIT :limit""", now, now.plus(Duration.ofDays(61)));
        return each(rows, (app, s) -> {
            YoonProperties.Billing b = billing(app);
            boolean trial = s.status().equals("TRIALING");
            if ((trial || s.status().equals("ACTIVE")) && !s.cancelAtPeriodEnd() && s.currentPeriodEnd() != null
                    && s.currentPeriodEnd().isAfter(now) && !s.currentPeriodEnd().equals(s.remindedPeriodEnd())
                    && !now.isBefore(reminderAt(s.currentPeriodEnd(), b))) {
                transitions.update(app, s.id(), Cause.sweep, trial ? "trial ending reminder" : "renewal reminder",
                        Map.of("reminded_period_end", s.currentPeriodEnd()),
                        trial ? "subscription.trial_will_end" : "subscription.renewal_upcoming");
                return true;
            }
            return false;
        });
    }

    /**
     * Period (or trial, ADR-0028) over: canceled if asked, else past due with the scheduled change
     * applied and an invoice to pay — the first one after a trial, a renewal otherwise.
     */
    public int endPeriods() {
        Instant now = clock.instant();
        List<Row> rows = candidates("""
                SELECT id, application_id FROM subscriptions
                WHERE status IN ('ACTIVE', 'TRIALING') AND current_period_end IS NOT NULL AND current_period_end <= :now
                ORDER BY current_period_end LIMIT :limit""", now, null);
        return each(rows, (app, s) -> {
            boolean trial = s.status().equals("TRIALING");
            if (!(trial || s.status().equals("ACTIVE")) || s.currentPeriodEnd() == null || s.currentPeriodEnd().isAfter(now)) {
                return false; // renewed meanwhile
            }
            if (s.cancelAtPeriodEnd()) {
                Map<String, Object> fields = new HashMap<>();
                fields.put("canceled_at", s.currentPeriodEnd());
                fields.put("cancel_at_period_end", false);
                transitions.apply(app, s.id(), SubscriptionStatus.CANCELED, Cause.sweep,
                        trial ? "canceled at trial end" : "canceled at period end", fields);
                return true;
            }
            Map<String, Object> fields = new HashMap<>();
            if (s.pendingPriceId() != null) {
                fields.put("price_id", s.pendingPriceId());
                fields.put("pending_price_id", null);
            }
            transitions.apply(app, s.id(), SubscriptionStatus.PAST_DUE, Cause.sweep,
                    trial ? "trial ended unpaid" : "period ended unpaid", fields);
            // Something to pay: the first invoice after a trial, else the renewal, at the next period's price.
            service.openInvoice(app, subscriptions.find(app.id(), s.id()).orElseThrow(), trial ? "FIRST" : "RENEWAL");
            return true;
        });
    }

    /** Grace over, still unpaid: expired. The open invoice stays open — a late payment re-activates. */
    public int endGrace() {
        Instant now = clock.instant();
        List<Row> rows = candidates("""
                SELECT id, application_id FROM subscriptions
                WHERE status = 'PAST_DUE' AND current_period_end IS NOT NULL
                  AND current_period_end + make_interval(days => grace_period_days) <= :now
                ORDER BY current_period_end LIMIT :limit""", now, null);
        return each(rows, (app, s) -> {
            if (!s.status().equals("PAST_DUE") || s.currentPeriodEnd() == null
                    || s.currentPeriodEnd().plus(Duration.ofDays(s.gracePeriodDays())).isAfter(now)) {
                return false;
            }
            transitions.apply(app, s.id(), SubscriptionStatus.EXPIRED, Cause.sweep, "grace period over", Map.of());
            return true;
        });
    }

    /** A first invoice left unpaid past the application's invoice TTL: the subscription expires. */
    public int expireIncomplete() {
        Instant now = clock.instant();
        List<Row> rows = candidates("""
                SELECT id, application_id FROM subscriptions
                WHERE status = 'INCOMPLETE' AND created_at <= :now
                ORDER BY created_at LIMIT :limit""", now, null);
        return each(rows, (app, s) -> {
            if (!s.status().equals("INCOMPLETE") || s.createdAt().plus(billing(app).invoiceTtl()).isAfter(now)) {
                return false;
            }
            transitions.apply(app, s.id(), SubscriptionStatus.EXPIRED, Cause.sweep, "first invoice not paid in time", Map.of());
            return true;
        });
    }

    // ------------------------------------------------------------------ helpers

    private record Row(String id, UUID applicationId) {
    }

    private interface Step {
        boolean run(AppPrincipal app, SubscriptionRecord locked);
    }

    private List<Row> candidates(String sql, Instant now, Instant horizon) {
        var spec = jdbc.sql(sql).param("now", java.sql.Timestamp.from(now)).param("limit", BATCH);
        if (horizon != null) {
            spec = spec.param("horizon", java.sql.Timestamp.from(horizon));
        }
        return spec.query(Row.class).list();
    }

    /** Runs a step per row, each in its own transaction under the row lock; one failure does not stop the rest. */
    private int each(List<Row> rows, Step step) {
        int done = 0;
        for (Row row : rows) {
            try {
                AppPrincipal app = applications.find(row.applicationId()).orElse(null);
                if (app == null) {
                    continue;
                }
                Boolean changed = tx.execute(t -> step.run(app, subscriptions.lock(app.id(), row.id()).orElseThrow()));
                if (Boolean.TRUE.equals(changed)) {
                    done++;
                }
            } catch (RuntimeException e) {
                meters.counter("yoon.billing.sweep.errors").increment();
                log.error("Billing sweep failed for subscription {}", row.id(), e);
            }
        }
        return done;
    }

    private YoonProperties.Billing billing(AppPrincipal app) {
        return properties.app(app.name()).billing();
    }

    /** "N days before" counted in the application's time zone: from the start of that day there. */
    static Instant reminderAt(Instant periodEnd, YoonProperties.Billing b) {
        ZonedDateTime local = periodEnd.atZone(b.timezone()).truncatedTo(ChronoUnit.DAYS);
        return local.minusDays(b.reminderDays()).toInstant();
    }
}
