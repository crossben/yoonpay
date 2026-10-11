package dev.yoonpay.core.lifecycle;

import org.junit.jupiter.api.Test;

import static dev.yoonpay.core.lifecycle.SubscriptionStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubscriptionStatusTest {

    @Test
    void every_transition_is_explicit() {
        // columns: INCOMPLETE ACTIVE PAST_DUE EXPIRED CANCELED
        TransitionTable.verify(SubscriptionStatus.values(), """
                INCOMPLETE I A X A A
                ACTIVE     X I A X A
                PAST_DUE   X A I A A
                EXPIRED    X A I I I
                CANCELED   X A I I I
                """, SubscriptionStatus::decide);
    }

    @Test
    void money_received_late_still_reactivates() {
        assertThat(EXPIRED.decide(ACTIVE)).isEqualTo(Decision.APPLY);
        assertThat(CANCELED.decide(ACTIVE)).isEqualTo(Decision.APPLY);
    }

    @Test
    void an_active_subscription_never_expires_without_passing_through_grace() {
        assertThatThrownBy(() -> ACTIVE.decide(EXPIRED)).isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    void nothing_goes_back_to_incomplete() {
        for (SubscriptionStatus s : values()) {
            if (s != INCOMPLETE) {
                assertThatThrownBy(() -> s.decide(INCOMPLETE)).isInstanceOf(IllegalTransitionException.class);
            }
        }
    }

    @Test
    void grace_is_entitled() {
        assertThat(ACTIVE.isEntitled()).isTrue();
        assertThat(PAST_DUE.isEntitled()).isTrue();
        assertThat(INCOMPLETE.isEntitled()).isFalse();
        assertThat(EXPIRED.isEntitled()).isFalse();
        assertThat(CANCELED.isEntitled()).isFalse();
    }
}
