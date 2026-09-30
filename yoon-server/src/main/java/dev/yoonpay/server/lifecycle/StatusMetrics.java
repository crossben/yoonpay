package dev.yoonpay.server.lifecycle;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Business metrics for dashboards and alerts:
 * <ul>
 *   <li>{@code yoon_status_changes_total{resource, status, provider}} — every applied status change;</li>
 *   <li>{@code yoon_outbox_pending}, {@code yoon_outbox_dead} — events waiting for / given up on delivery;</li>
 *   <li>{@code yoon_payments_open} — payments created or pending;</li>
 *   <li>{@code yoon_payouts_needs_review} — payouts a human must resolve.</li>
 * </ul>
 * Gauges are counted in the database at scrape time (partial indexes keep them cheap).
 */
@Component
public class StatusMetrics {

    private final MeterRegistry meters;

    public StatusMetrics(MeterRegistry meters, JdbcClient jdbc) {
        this.meters = meters;
        gauge(jdbc, "yoon.outbox.pending", "Outbound events waiting for delivery",
                "SELECT count(*) FROM outbound_events WHERE delivery_status = 'PENDING'");
        gauge(jdbc, "yoon.outbox.dead", "Outbound events that exhausted their delivery attempts",
                "SELECT count(*) FROM outbound_events WHERE delivery_status = 'DEAD'");
        gauge(jdbc, "yoon.payments.open", "Payments created or pending",
                "SELECT count(*) FROM payments WHERE status IN ('CREATED', 'PENDING')");
        gauge(jdbc, "yoon.payouts.needs_review", "Payouts waiting for a human",
                "SELECT count(*) FROM payouts WHERE needs_review");
    }

    public void statusChanged(String resource, String status, String provider) {
        meters.counter("yoon.status.changes", "resource", resource, "status", status.toLowerCase(),
                "provider", provider == null ? "none" : provider).increment();
    }

    private void gauge(JdbcClient jdbc, String name, String description, String sql) {
        Gauge.builder(name, () -> {
                    try {
                        return jdbc.sql(sql).query(Long.class).single();
                    } catch (RuntimeException e) {
                        return Double.NaN;
                    }
                })
                .description(description)
                .register(meters);
    }
}
