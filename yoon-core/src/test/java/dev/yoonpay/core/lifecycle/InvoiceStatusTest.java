package dev.yoonpay.core.lifecycle;

import org.junit.jupiter.api.Test;

import static dev.yoonpay.core.lifecycle.InvoiceStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class InvoiceStatusTest {

    @Test
    void every_transition_is_explicit() {
        // columns: OPEN PAID VOID UNCOLLECTIBLE
        TransitionTable.verify(InvoiceStatus.values(), """
                OPEN          I A A A
                PAID          X I I I
                VOID          X A I I
                UNCOLLECTIBLE X A I I
                """, InvoiceStatus::decide);
    }

    @Test
    void a_paid_invoice_is_never_undone() {
        assertThat(PAID.decide(VOID)).isEqualTo(Decision.IGNORE);
    }

    @Test
    void a_late_payment_of_a_voided_invoice_is_recorded() {
        assertThat(VOID.decide(PAID)).isEqualTo(Decision.APPLY);
    }
}
