package dev.yoonpay.server.settlement;

import dev.yoonpay.core.lifecycle.PaymentStatus;
import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.auth.Applications;
import dev.yoonpay.server.config.SweepProperties;
import dev.yoonpay.server.idempotency.IdempotencyStore;
import dev.yoonpay.server.lifecycle.StatusEvents.Cause;
import dev.yoonpay.server.payment.PaymentRecord;
import dev.yoonpay.server.payment.PaymentRepository;
import dev.yoonpay.server.payment.PaymentTransitions;
import dev.yoonpay.server.payout.PayoutRecord;
import dev.yoonpay.server.payout.PayoutTransitions;
import dev.yoonpay.server.refund.RefundRecord;
import dev.yoonpay.server.settlement.Settlement.Outcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The fallback half of settlement: finds whatever webhooks did not resolve and asks the
 * provider. Stuck payments settle without human action; only payouts that stay unknown are
 * escalated to a person.
 */
@Component
public class Reconciler {

    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);
    private static final int BATCH = 100;

    private final JdbcClient jdbc;
    private final Settlement settlement;
    private final Applications applications;
    private final PaymentRepository payments;
    private final PaymentTransitions paymentTransitions;
    private final PayoutTransitions payoutTransitions;
    private final IdempotencyStore idempotency;
    private final SweepProperties sweep;
    private final Clock clock;

    public Reconciler(JdbcClient jdbc, Settlement settlement, Applications applications, PaymentRepository payments,
                      PaymentTransitions paymentTransitions, PayoutTransitions payoutTransitions,
                      IdempotencyStore idempotency, SweepProperties sweep, Clock clock) {
        this.jdbc = jdbc;
        this.settlement = settlement;
        this.applications = applications;
        this.payments = payments;
        this.paymentTransitions = paymentTransitions;
        this.payoutTransitions = payoutTransitions;
        this.idempotency = idempotency;
        this.sweep = sweep;
        this.clock = clock;
    }

    /** Open payments: ask the provider; expire those still unpaid (or unanswerable) past their TTL. */
    public int sweepPayments() {
        Instant now = clock.instant();
        List<PaymentRecord> open = jdbc.sql("""
                        SELECT p.*, 0 AS amount_refunded FROM payments p
                        WHERE p.status IN ('CREATED', 'PENDING') AND p.updated_at < :before
                        ORDER BY p.updated_at LIMIT :batch""")
                .param("before", Timestamp.from(now.minus(sweep.pendingAfter()))).param("batch", BATCH)
                .query(PaymentRecord.class).list();

        for (PaymentRecord p : open) {
            Optional<AppPrincipal> app = applications.find(p.applicationId());
            if (app.isEmpty()) {
                continue;
            }
            if (p.status().equals("CREATED")) {
                recoverInterrupted(app.get(), p);
                continue;
            }
            Outcome outcome = settlement.settlePayment(app.get(), p, Cause.sweep);
            boolean pastTtl = p.createdAt().isBefore(now.minus(sweep.paymentTtl()));
            if (pastTtl && outcome == Outcome.STILL_OPEN) {
                paymentTransitions.apply(app.get(), p.id(), PaymentStatus.EXPIRED, Cause.sweep, null,
                        "provider still reports unpaid after " + sweep.paymentTtl(), Map.of());
            } else if (pastTtl && outcome == Outcome.NO_ANSWER && p.statusChecks() + 1 >= sweep.maxNoAnswer()) {
                paymentTransitions.apply(app.get(), p.id(), PaymentStatus.EXPIRED, Cause.sweep, null,
                        "no provider answer after " + (p.statusChecks() + 1) + " checks; will still accept a later success",
                        Map.of());
            }
        }
        return open.size();
    }

    /**
     * A payment left in CREATED means the process stopped mid-creation. If a provider call may
     * have been sent, treat it as an unknown outcome; if none was, it never left Yoon.
     */
    private void recoverInterrupted(AppPrincipal app, PaymentRecord p) {
        if (payments.hasAttempts(p.id())) {
            String provider = jdbc.sql("SELECT provider FROM payment_attempts WHERE payment_id = :p ORDER BY id DESC LIMIT 1")
                    .param("p", p.id()).query(String.class).single();
            Map<String, Object> fields = new HashMap<>();
            fields.put("provider", provider);
            paymentTransitions.apply(app, p.id(), PaymentStatus.PENDING, Cause.sweep, null,
                    "creation interrupted after contacting " + provider + "; outcome unknown", fields);
        } else {
            paymentTransitions.apply(app, p.id(), PaymentStatus.FAILED, Cause.sweep, null,
                    "creation interrupted before any provider was contacted",
                    Map.of("failure_code", "interrupted", "failure_message", "Payment creation was interrupted; no provider was contacted"));
        }
    }

    /** Failed/expired payments are asked once more, a while later: a late success is still money received. */
    public int recheckFinalPayments() {
        Instant now = clock.instant();
        List<PaymentRecord> ended = jdbc.sql("""
                        SELECT p.*, 0 AS amount_refunded FROM payments p
                        WHERE p.status IN ('FAILED', 'EXPIRED') AND NOT p.late_check_done AND p.provider IS NOT NULL
                          AND p.updated_at < :after AND p.updated_at > :window
                        ORDER BY p.updated_at LIMIT :batch""")
                .param("after", Timestamp.from(now.minus(sweep.recheckAfter())))
                .param("window", Timestamp.from(now.minus(sweep.recheckWindow())))
                .param("batch", BATCH)
                .query(PaymentRecord.class).list();
        for (PaymentRecord p : ended) {
            applications.find(p.applicationId()).ifPresent(app -> settlement.settlePayment(app, p, Cause.sweep));
            payments.markLateCheckDone(p.id());
        }
        return ended.size();
    }

    public int sweepRefunds() {
        List<RefundRecord> open = jdbc.sql("""
                        SELECT * FROM refunds WHERE status IN ('PENDING', 'UNKNOWN') AND updated_at < :before
                        ORDER BY updated_at LIMIT :batch""")
                .param("before", Timestamp.from(clock.instant().minus(sweep.pendingAfter()))).param("batch", BATCH)
                .query(RefundRecord.class).list();
        for (RefundRecord r : open) {
            applications.find(r.applicationId()).ifPresent(app -> settlement.settleRefund(app, r, Cause.sweep));
        }
        return open.size();
    }

    /** Open payouts: ask the provider; flag for a human those still open past the review threshold. */
    public int sweepPayouts() {
        Instant now = clock.instant();
        List<PayoutRecord> open = jdbc.sql("""
                        SELECT * FROM payouts WHERE status IN ('PROCESSING', 'UNKNOWN') AND updated_at < :before
                        ORDER BY updated_at LIMIT :batch""")
                .param("before", Timestamp.from(now.minus(sweep.pendingAfter()))).param("batch", BATCH)
                .query(PayoutRecord.class).list();
        for (PayoutRecord po : open) {
            Optional<AppPrincipal> app = applications.find(po.applicationId());
            if (app.isEmpty()) {
                continue;
            }
            Outcome outcome = settlement.settlePayout(app.get(), po, Cause.sweep);
            boolean stillOpen = outcome == Outcome.STILL_OPEN || outcome == Outcome.NO_ANSWER;
            if (stillOpen && po.createdAt().isBefore(now.minus(sweep.payoutReviewAfter()))) {
                payoutTransitions.flagForReview(app.get(), po.id(),
                        po.status().toLowerCase() + " for more than " + sweep.payoutReviewAfter());
            }
        }
        return open.size();
    }

    /** Retention: completed idempotency keys and old raw inbound webhooks. */
    public void purge() {
        int keys = idempotency.purgeCompletedOlderThan(sweep.idempotencyRetention());
        int hooks = jdbc.sql("DELETE FROM inbound_webhooks WHERE processed_at IS NOT NULL AND received_at < :before")
                .param("before", Timestamp.from(clock.instant().minus(sweep.inboundRetention()))).update();
        if (keys + hooks > 0) {
            log.info("Purged {} idempotency keys and {} inbound webhooks", keys, hooks);
        }
    }

}
