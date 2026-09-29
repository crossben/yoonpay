package dev.yoonpay.server.settlement;

import dev.yoonpay.server.outbox.OutboxDelivery;
import dev.yoonpay.server.webhook.InboundWebhookProcessor;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import javax.sql.DataSource;

/**
 * Background work. Queue workers (inbound webhooks, outbound events) run on every node and
 * share rows through leases; sweeps run on one node at a time (ShedLock). Disable with
 * {@code YOON_SCHEDULING_ENABLED=false} (tests drive the same methods directly).
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
@ConditionalOnProperty(name = "yoon.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class Scheduler {

    private final InboundWebhookProcessor inbound;
    private final OutboxDelivery outbox;
    private final Reconciler reconciler;

    public Scheduler(InboundWebhookProcessor inbound, OutboxDelivery outbox, Reconciler reconciler) {
        this.inbound = inbound;
        this.outbox = outbox;
        this.reconciler = reconciler;
    }

    @Bean
    static LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new org.springframework.jdbc.core.JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }

    @Scheduled(fixedDelayString = "PT2S")
    void inboundWebhooks() {
        while (inbound.processPending(50) == 50) {
            // drain
        }
    }

    @Scheduled(fixedDelayString = "PT2S")
    void outboundEvents() {
        while (outbox.deliverDue(50) == 50) {
            // drain
        }
    }

    @Scheduled(fixedDelayString = "PT30S")
    @SchedulerLock(name = "sweep-payments")
    void payments() {
        reconciler.sweepPayments();
        reconciler.recheckFinalPayments();
    }

    @Scheduled(fixedDelayString = "PT30S")
    @SchedulerLock(name = "sweep-refunds")
    void refunds() {
        reconciler.sweepRefunds();
    }

    @Scheduled(fixedDelayString = "PT30S")
    @SchedulerLock(name = "sweep-payouts")
    void payouts() {
        reconciler.sweepPayouts();
    }

    @Scheduled(fixedDelayString = "PT1H")
    @SchedulerLock(name = "purge")
    void purge() {
        reconciler.purge();
    }
}
