package dev.yoonpay.core.billing;

import dev.yoonpay.core.lifecycle.SubscriptionStatus;

import java.time.Instant;
import java.util.Objects;

/** When a newly paid period starts (ADR-0026, "Stacking"). */
public final class Stacking {

    private Stacking() {
    }

    /**
     * @param status          the subscription's status when the payment is confirmed
     * @param currentPeriodEnd the end of its current period, or null if it never had one
     * @param paidAt          when the payment was confirmed
     */
    public static Instant nextPeriodStart(SubscriptionStatus status, Instant currentPeriodEnd, Instant paidAt) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(paidAt, "paidAt");
        return switch (status) {
            // Paying early never loses days; paying in grace does not get the grace days free.
            case ACTIVE, PAST_DUE -> currentPeriodEnd != null ? currentPeriodEnd : paidAt;
            // A first payment, or one after the subscription ended: a fresh period from now.
            case INCOMPLETE, EXPIRED, CANCELED -> paidAt;
        };
    }
}
