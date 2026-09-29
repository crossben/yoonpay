package dev.yoonpay.provider.naboopay;

import dev.yoonpay.core.lifecycle.PaymentStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Keep in sync with docs/providers/naboopay.md. */
class NabooPayStatusTest {

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "paid,      SUCCEEDED",
            "done,      SUCCEEDED",
            "PAID,      SUCCEEDED",
            "pending,   PENDING",
            "part_paid, PENDING",
            "cancel,    FAILED",
            "cancelled, FAILED",
            "canceled,  FAILED",
            "failed,    FAILED",
            "failure,   FAILED",
            "rejected,  FAILED",
            "refused,   FAILED",
            "error,     FAILED",
            "expired,   EXPIRED",
            "escrowed,  null",
            "null,      null",
    })
    void transaction_statuses(String raw, String expected) {
        assertThat(NabooPayStatus.of(raw)).isEqualTo(expected == null ? null : PaymentStatus.valueOf(expected));
    }
}
