package dev.yoonpay.provider.dexpay;

import dev.yoonpay.core.lifecycle.PaymentStatus;
import dev.yoonpay.core.lifecycle.PayoutStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Keep in sync with docs/providers/dexpay.md. */
class DexPayStatusTest {

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "success,              SUCCEEDED",
            "succeeded,            SUCCEEDED",
            "completed,            SUCCEEDED",
            "PAID,                 SUCCEEDED",
            "pending,              PENDING",
            "initiated,            PENDING",
            "processing,           PENDING",
            "pending_confirmation, PENDING",
            "failed,               FAILED",
            "error,                FAILED",
            "declined,             FAILED",
            "cancelled,            FAILED",
            "canceled,             FAILED",
            "expired,              EXPIRED",
            "refunded,             null",
            "mystery,              null",
            "null,                 null",
    })
    void checkout_statuses(String raw, String expected) {
        assertThat(DexPayStatus.checkout(raw)).isEqualTo(expected == null ? null : PaymentStatus.valueOf(expected));
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "completed,  PAID",
            "success,    PAID",
            "successful, PAID",
            "FAILED,     FAILED",
            "cancelled,  FAILED",
            "canceled,   FAILED",
            "rejected,   FAILED",
            "pending,    PROCESSING",
            "processing, PROCESSING",
            "frozen,     PROCESSING",
            "mystery,    null",
            "null,       null",
    })
    void payout_statuses(String raw, String expected) {
        assertThat(DexPayStatus.payout(raw)).isEqualTo(expected == null ? null : PayoutStatus.valueOf(expected));
    }
}
