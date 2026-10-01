package dev.yoonpay.provider.wave;

import dev.yoonpay.core.lifecycle.PaymentStatus;
import dev.yoonpay.core.lifecycle.PayoutStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Same tables as docs/providers/wave.md. */
class WaveStatusTest {

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "succeeded, complete, SUCCEEDED", "cancelled, open, FAILED", "cancelled, expired, FAILED",
            "processing, open, PENDING", "processing, expired, EXPIRED", "processing, null, PENDING",
            "refunded, complete, null", "null, open, null"})
    void checkout(String payment, String checkout, PaymentStatus expected) {
        assertThat(WaveStatus.checkout(payment, checkout)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "processing, PROCESSING", "succeeded, PAID", "failed, FAILED", "reversed, null", "other, null", "null, null"})
    void payout(String raw, PayoutStatus expected) {
        assertThat(WaveStatus.payout(raw)).isEqualTo(expected);
    }
}
