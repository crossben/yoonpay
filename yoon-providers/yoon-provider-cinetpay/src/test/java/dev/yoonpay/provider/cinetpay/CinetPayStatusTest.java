package dev.yoonpay.provider.cinetpay;

import dev.yoonpay.core.lifecycle.PaymentStatus;
import dev.yoonpay.core.lifecycle.PayoutStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Same tables as docs/providers/cinetpay.md. */
class CinetPayStatusTest {

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "SUCCESS, SUCCEEDED", "INITIATED, PENDING", "PENDING, PENDING", "FAILED, FAILED", "EXPIRED, EXPIRED",
            "OTP_ERROR, null", "other, null", "null, null"})
    void payment(String raw, PaymentStatus expected) {
        assertThat(CinetPayStatus.payment(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "SUCCESS, PAID", "INITIATED, PROCESSING", "PENDING, PROCESSING", "FAILED, FAILED", "EXPIRED, null",
            "other, null", "null, null"})
    void transfer(String raw, PayoutStatus expected) {
        assertThat(CinetPayStatus.transfer(raw)).isEqualTo(expected);
    }

    @Test
    void operators_map_per_country() {
        assertThat(CinetPayProvider.operator("orange_money", "CI")).isEqualTo("OM_CI");
        assertThat(CinetPayProvider.operator("wave", "SN")).isEqualTo("WAVE_SN");
        assertThat(CinetPayProvider.operator("wave", "ML")).isNull();
        assertThat(CinetPayProvider.operator("card", "CI")).isNull();
    }

    @Test
    void long_references_map_to_a_fixed_30_character_id() {
        String ref = "po_0199a3f0c2e47a1b9c3d5e6f7a8b9c0d";
        assertThat(CinetPayProvider.txId(ref)).hasSize(30).startsWith("y").isEqualTo(CinetPayProvider.txId(ref));
        assertThat(CinetPayProvider.txId("short_ref")).isEqualTo("short_ref");
    }
}
