package dev.yoonpay.provider.pispi;

import dev.yoonpay.core.lifecycle.PaymentStatus;
import dev.yoonpay.core.lifecycle.PayoutStatus;
import dev.yoonpay.core.lifecycle.RefundStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Same tables as docs/providers/pispi.md. */
class PiSpiStatusTest {

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "INITIE, PENDING", "ENVOYE, PENDING", "IRREVOCABLE, SUCCEEDED", "REJETE, FAILED", "ANNULE, FAILED",
            "CONFIRME, null", "null, null"})
    void request(String raw, PaymentStatus expected) {
        assertThat(PiSpiStatus.request(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "INITIE, PROCESSING", "ENVOYE, PROCESSING", "IRREVOCABLE, PAID", "REJETE, FAILED", "ANNULE, FAILED",
            "other, null", "null, null"})
    void payout(String raw, PayoutStatus expected) {
        assertThat(PiSpiStatus.payout(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "INITIE, PENDING", "ENVOYE, PENDING", "IRREVOCABLE, REFUNDED", "REJETE, FAILED", "ANNULE, null",
            "null, null"})
    void refund(String raw, RefundStatus expected) {
        assertThat(PiSpiStatus.refund(raw)).isEqualTo(expected);
    }
}
