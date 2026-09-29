package dev.yoonpay.provider.paydunya;

import dev.yoonpay.core.lifecycle.PaymentStatus;
import dev.yoonpay.core.lifecycle.PayoutStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Every status PayDunya is known to send, plus values it does not document. Keep in sync with docs/providers/paydunya.md. */
class PayDunyaStatusTest {

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "pending,   PENDING",
            "completed, SUCCEEDED",
            "COMPLETED, SUCCEEDED",
            "cancelled, FAILED",
            "canceled,  FAILED",
            "failed,    FAILED",
            "expired,   EXPIRED",
            "refunded,  null",
            "'',        null",
            "null,      null",
    })
    void invoice_statuses(String raw, String expected) {
        assertThat(PayDunyaStatus.invoice(raw)).isEqualTo(expected == null ? null : PaymentStatus.valueOf(expected));
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "success,    PAID",
            "completed,  PAID",
            "failed,     FAILED",
            "failure,    FAILED",
            "cancelled,  FAILED",
            "canceled,   FAILED",
            "declined,   FAILED",
            "rejected,   FAILED",
            "error,      FAILED",
            "pending,    PROCESSING",
            "processing, PROCESSING",
            "created,    PROCESSING",
            "whatever,   null",
            "null,       null",
    })
    void disburse_statuses(String raw, String expected) {
        assertThat(PayDunyaStatus.disburse(raw)).isEqualTo(expected == null ? null : PayoutStatus.valueOf(expected));
    }
}
