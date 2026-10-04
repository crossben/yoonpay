package dev.yoonpay.provider.stripe;

import dev.yoonpay.core.lifecycle.PaymentStatus;
import dev.yoonpay.core.lifecycle.RefundStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Same tables as docs/providers/stripe.md. */
class StripeStatusTest {

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "open, unpaid, PENDING", "complete, paid, SUCCEEDED", "complete, unpaid, PENDING",
            "complete, no_payment_required, null", "expired, unpaid, EXPIRED", "other, paid, null", "null, paid, null"})
    void checkout(String status, String paymentStatus, PaymentStatus expected) {
        assertThat(StripeStatus.checkout(status, paymentStatus)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "pending, PENDING", "requires_action, PENDING", "succeeded, REFUNDED", "failed, FAILED",
            "canceled, FAILED", "other, null", "null, null"})
    void refund(String raw, RefundStatus expected) {
        assertThat(StripeStatus.refund(raw)).isEqualTo(expected);
    }
}
