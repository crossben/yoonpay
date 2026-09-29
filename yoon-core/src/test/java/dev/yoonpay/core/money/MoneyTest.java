package dev.yoonpay.core.money;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void xof_amounts_are_whole_francs() {
        Money m = Money.of(5000, "XOF");

        assertThat(m.amount()).isEqualTo(5000);
        assertThat(m.currency().getDefaultFractionDigits()).isZero();
        assertThat(m).hasToString("5000 XOF");
    }

    @Test
    void negative_amounts_are_rejected() {
        assertThatThrownBy(() -> Money.of(-1, "XOF")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void arithmetic_refuses_mixed_currencies() {
        assertThatThrownBy(() -> Money.of(1, "XOF").plus(Money.of(1, "EUR")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Currency mismatch");
    }

    @Test
    void arithmetic_fails_loudly_instead_of_overflowing() {
        assertThatThrownBy(() -> Money.of(Long.MAX_VALUE, "XOF").plus(Money.of(1, "XOF")))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void minus_below_zero_is_rejected() {
        assertThatThrownBy(() -> Money.of(100, "XOF").minus(Money.of(200, "XOF")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void compares_within_a_currency() {
        assertThat(Money.of(200, "XOF").isGreaterThan(Money.of(100, "XOF"))).isTrue();
        assertThat(Money.of(100, "XOF").isGreaterThan(Money.of(100, "XOF"))).isFalse();
    }
}
