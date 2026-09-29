package dev.yoonpay.core.lifecycle;

import org.junit.jupiter.api.Test;

import static dev.yoonpay.core.lifecycle.PaymentStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class PaymentStatusTest {

    @Test
    void every_transition_is_explicit() {
        // columns: CREATED PENDING SUCCEEDED FAILED EXPIRED
        TransitionTable.verify(PaymentStatus.values(), """
                CREATED   I A X A X
                PENDING   X I A A A
                SUCCEEDED X X I I I
                FAILED    X X A I I
                EXPIRED   X X A I I
                """, PaymentStatus::decide);
    }

    @Test
    void late_failure_after_success_is_ignored_not_applied() {
        assertThat(SUCCEEDED.decide(FAILED)).isEqualTo(Decision.IGNORE);
    }

    @Test
    void late_success_after_expiry_or_failure_is_applied() {
        assertThat(EXPIRED.decide(SUCCEEDED)).isEqualTo(Decision.APPLY);
        assertThat(FAILED.decide(SUCCEEDED)).isEqualTo(Decision.APPLY);
    }

    @Test
    void created_cannot_jump_to_succeeded_without_going_through_pending() {
        TransitionTable.assertIllegal(CREATED, SUCCEEDED, PaymentStatus::decide);
    }

    @Test
    void only_succeeded_failed_and_expired_are_final() {
        assertThat(CREATED.isFinal()).isFalse();
        assertThat(PENDING.isFinal()).isFalse();
        assertThat(SUCCEEDED.isFinal()).isTrue();
        assertThat(FAILED.isFinal()).isTrue();
        assertThat(EXPIRED.isFinal()).isTrue();
    }
}
