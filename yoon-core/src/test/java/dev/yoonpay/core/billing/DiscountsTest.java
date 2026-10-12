package dev.yoonpay.core.billing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscountsTest {

    @ParameterizedTest(name = "{1}% of {0} = {2} off")
    @CsvSource({
            "15000, 33, 4950",
            "15000, 20, 3000",
            "15000, 100, 15000",
            "99, 50, 49",       // floor: the merchant keeps the odd minor unit
            "1, 99, 0",
            "1, 100, 1",
    })
    void percent_off_is_floored(long subtotal, int percent, long off) {
        assertThat(new Discounts.Discount(percent, null).amountOff(subtotal)).isEqualTo(off);
    }

    @Test
    void percent_off_never_overflows() {
        long huge = Long.MAX_VALUE - 7;
        assertThat(new Discounts.Discount(100, null).amountOff(huge)).isEqualTo(huge);
        assertThat(new Discounts.Discount(50, null).amountOff(huge)).isEqualTo(huge / 2);
    }

    @Test
    void amount_off_never_goes_below_zero() {
        assertThat(new Discounts.Discount(null, 2000L).amountOff(15000)).isEqualTo(2000);
        assertThat(new Discounts.Discount(null, 20000L).amountOff(15000)).isEqualTo(15000);
    }

    @Test
    void a_discount_is_exactly_one_of_percent_and_amount() {
        assertThatThrownBy(() -> new Discounts.Discount(null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Discounts.Discount(10, 100L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Discounts.Discount(0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Discounts.Discount(101, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Discounts.Discount(null, 0L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void periods_count_down_and_forever_never_ends() {
        assertThat(Discounts.initialPeriods(Discounts.Duration.ONCE, null)).isEqualTo(1);
        assertThat(Discounts.initialPeriods(Discounts.Duration.REPEATING, 3)).isEqualTo(3);
        assertThat(Discounts.initialPeriods(Discounts.Duration.FOREVER, null)).isNull();

        assertThat(Discounts.afterInvoice(3)).isEqualTo(2);
        assertThat(Discounts.afterInvoice(1)).isZero();
        assertThat(Discounts.afterInvoice(null)).isNull();
        assertThat(Discounts.applies(0)).isFalse();
        assertThat(Discounts.applies(1)).isTrue();
        assertThat(Discounts.applies(null)).isTrue();
    }

    @Test
    void repeating_needs_a_period_count_and_the_others_refuse_one() {
        assertThatThrownBy(() -> Discounts.initialPeriods(Discounts.Duration.REPEATING, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Discounts.initialPeriods(Discounts.Duration.REPEATING, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Discounts.initialPeriods(Discounts.Duration.REPEATING, 121)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Discounts.initialPeriods(Discounts.Duration.ONCE, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Discounts.initialPeriods(Discounts.Duration.FOREVER, 2)).isInstanceOf(IllegalArgumentException.class);
    }
}
