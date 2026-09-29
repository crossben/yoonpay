package dev.yoonpay.core.lifecycle;

import org.junit.jupiter.api.Test;

import static dev.yoonpay.core.lifecycle.PayoutStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class PayoutStatusTest {

    @Test
    void every_transition_is_explicit() {
        // columns: CREATED PROCESSING UNKNOWN PAID FAILED
        TransitionTable.verify(PayoutStatus.values(), """
                CREATED    I A A X A
                PROCESSING X I A A A
                UNKNOWN    X I I A A
                PAID       X I I I I
                FAILED     X I I A I
                """, PayoutStatus::decide);
    }

    @Test
    void submit_timeout_goes_to_unknown_not_failed() {
        assertThat(CREATED.decide(UNKNOWN)).isEqualTo(Decision.APPLY);
        assertThat(PROCESSING.decide(UNKNOWN)).isEqualTo(Decision.APPLY);
    }

    @Test
    void unknown_is_resolved_only_by_a_definite_answer() {
        assertThat(UNKNOWN.decide(PAID)).isEqualTo(Decision.APPLY);
        assertThat(UNKNOWN.decide(FAILED)).isEqualTo(Decision.APPLY);
        assertThat(UNKNOWN.decide(PROCESSING)).isEqualTo(Decision.IGNORE);
    }

    @Test
    void money_that_left_is_recorded_even_after_a_failure() {
        assertThat(FAILED.decide(PAID)).isEqualTo(Decision.APPLY);
        assertThat(PAID.decide(FAILED)).isEqualTo(Decision.IGNORE);
    }
}
