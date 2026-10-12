package dev.yoonpay.core.billing;

import dev.yoonpay.core.lifecycle.SubscriptionStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static dev.yoonpay.core.billing.Stacking.nextPeriodStart;
import static org.assertj.core.api.Assertions.assertThat;

class StackingTest {

    private static final Instant END = Instant.parse("2026-10-31T00:00:00Z");
    private static final Instant EARLY = Instant.parse("2026-10-20T09:00:00Z");
    private static final Instant LATE = Instant.parse("2026-11-02T09:00:00Z");

    @Test
    void paying_early_extends_from_the_period_end() {
        assertThat(nextPeriodStart(SubscriptionStatus.ACTIVE, END, EARLY)).isEqualTo(END);
    }

    @Test
    void paying_in_grace_extends_from_the_old_period_end() {
        assertThat(nextPeriodStart(SubscriptionStatus.PAST_DUE, END, LATE)).isEqualTo(END);
    }

    @Test
    void paying_after_the_end_starts_now() {
        assertThat(nextPeriodStart(SubscriptionStatus.EXPIRED, END, LATE)).isEqualTo(LATE);
        assertThat(nextPeriodStart(SubscriptionStatus.CANCELED, END, LATE)).isEqualTo(LATE);
    }

    @Test
    void paying_during_a_trial_keeps_the_trial_days() {
        assertThat(nextPeriodStart(SubscriptionStatus.TRIALING, END, EARLY)).isEqualTo(END);
    }

    @Test
    void a_first_payment_starts_now() {
        assertThat(nextPeriodStart(SubscriptionStatus.INCOMPLETE, null, EARLY)).isEqualTo(EARLY);
    }
}
