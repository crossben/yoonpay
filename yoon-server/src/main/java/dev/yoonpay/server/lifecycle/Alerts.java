package dev.yoonpay.server.lifecycle;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Things a human should look at. Each is a WARN log line (ids only — no phone numbers, no
 * secrets) and a {@code yoon_alerts_total{type=…}} counter to alert on in Prometheus.
 */
@Component
public class Alerts {

    public enum Type {
        /** The provider confirmed a different amount than Yoon asked for. */
        amount_mismatch,
        /** A payment Yoon had marked failed/expired turned out to be paid. */
        late_success,
        /** A payout Yoon had marked failed turned out to be paid. */
        late_payout,
        /** A payout's outcome stayed unknown past the review threshold. */
        payout_needs_review,
        /** A provider reported a status its adapter does not map. */
        unmapped_provider_status,
        /** An outbound event exhausted its delivery attempts. */
        event_dead
    }

    private static final Logger log = LoggerFactory.getLogger(Alerts.class);
    private final MeterRegistry meters;

    public Alerts(MeterRegistry meters) {
        this.meters = meters;
    }

    public void raise(Type type, String resourceId, String detail) {
        meters.counter("yoon.alerts", "type", type.name()).increment();
        log.warn("ALERT {} resource={} {}", type, resourceId, detail);
    }

    public double count(Type type) {
        var c = meters.find("yoon.alerts").tag("type", type.name()).counter();
        return c == null ? 0 : c.count();
    }
}
