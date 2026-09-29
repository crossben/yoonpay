package dev.yoonpay.core.lifecycle;

import org.junit.jupiter.api.Test;

import static dev.yoonpay.core.lifecycle.RefundStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class RefundStatusTest {

    @Test
    void every_transition_is_explicit() {
        // columns: CREATED PENDING UNKNOWN REFUNDED FAILED
        TransitionTable.verify(RefundStatus.values(), """
                CREATED  I A A X A
                PENDING  X I A A A
                UNKNOWN  X I I A A
                REFUNDED X I I I I
                FAILED   X I I A I
                """, RefundStatus::decide);
    }

    @Test
    void refund_timeout_goes_to_unknown() {
        assertThat(PENDING.decide(UNKNOWN)).isEqualTo(Decision.APPLY);
    }
}
