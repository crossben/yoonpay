package dev.yoonpay.core.billing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;

import static dev.yoonpay.core.billing.BillingPeriods.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BillingPeriodsTest {

    private static Instant t(String iso) {
        return Instant.parse(iso);
    }

    @ParameterizedTest
    @CsvSource({
            // start, interval, count, anchor, expected end
            "2026-01-15T10:00:00Z, MONTH, 1, 15, 2026-02-15T10:00:00Z",
            "2026-01-31T10:00:00Z, MONTH, 1, 31, 2026-02-28T10:00:00Z",
            "2026-02-28T10:00:00Z, MONTH, 1, 31, 2026-03-31T10:00:00Z",
            "2028-01-31T00:00:00Z, MONTH, 1, 31, 2028-02-29T00:00:00Z",
            "2026-03-31T00:00:00Z, MONTH, 1, 31, 2026-04-30T00:00:00Z",
            "2026-04-30T00:00:00Z, MONTH, 1, 31, 2026-05-31T00:00:00Z",
            "2026-01-31T00:00:00Z, MONTH, 3, 31, 2026-04-30T00:00:00Z",
            "2026-08-31T00:00:00Z, MONTH, 6, 31, 2027-02-28T00:00:00Z",
            "2028-02-29T00:00:00Z, YEAR, 1, 29, 2029-02-28T00:00:00Z",
            "2029-02-28T00:00:00Z, YEAR, 1, 29, 2030-02-28T00:00:00Z",
            "2031-02-28T00:00:00Z, YEAR, 1, 29, 2032-02-29T00:00:00Z",
            "2026-12-31T23:59:59Z, MONTH, 1, 31, 2027-01-31T23:59:59Z",
            "2026-10-10T08:00:00Z, DAY, 30, 10, 2026-11-09T08:00:00Z",
            "2026-10-10T08:00:00Z, WEEK, 2, 10, 2026-10-24T08:00:00Z",
    })
    void period_ends(String start, Interval interval, int count, int anchor, String expected) {
        assertThat(periodEnd(t(start), interval, count, anchor)).isEqualTo(t(expected));
    }

    @Test
    void a_year_of_monthly_periods_anchored_on_the_31st_comes_back_to_the_31st() {
        Instant p = t("2026-01-31T00:00:00Z");
        String[] ends = {"02-28", "03-31", "04-30", "05-31", "06-30", "07-31", "08-31", "09-30", "10-31", "11-30", "12-31"};
        for (String e : ends) {
            p = periodEnd(p, Interval.MONTH, 1, 31);
            assertThat(p).isEqualTo(t("2026-" + e + "T00:00:00Z"));
        }
    }

    @Test
    void anchor_of_a_start() {
        assertThat(anchorOf(t("2026-01-31T12:00:00Z"))).isEqualTo(31);
    }

    @Test
    void bad_arguments_are_refused() {
        assertThatThrownBy(() -> periodEnd(t("2026-01-01T00:00:00Z"), Interval.MONTH, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> periodEnd(t("2026-01-01T00:00:00Z"), Interval.MONTH, 1, 32)).isInstanceOf(IllegalArgumentException.class);
    }

    private static final Duration MONTH = Duration.ofDays(30);
    private static final Duration YEAR = Duration.ofDays(365);

    @Test
    void upgrade_is_more_per_unit_of_time() {
        assertThat(isUpgrade(10_000, MONTH, 15_000, MONTH)).isTrue();
        assertThat(isUpgrade(15_000, MONTH, 10_000, MONTH)).isFalse();
        assertThat(isUpgrade(10_000, MONTH, 10_000, MONTH)).as("same price is not an upgrade").isFalse();
        // Yearly at 10 × monthly is cheaper per day: switching monthly → yearly is not an upgrade.
        assertThat(isUpgrade(15_000, MONTH, 150_000, YEAR)).isFalse();
        assertThat(isUpgrade(150_000, YEAR, 15_000, MONTH)).isTrue();
    }

    @Test
    void unused_time_becomes_whole_days_of_the_new_price_rounded_down() {
        // Half of a 10 000 / 30-day period left = 5 000 of value; new price 20 000 / 30 days → 7.5 days → 7.
        assertThat(extraDays(Duration.ofDays(15), 10_000, MONTH, 20_000, MONTH)).isEqualTo(7);
        // Same price: every remaining day carries over.
        assertThat(extraDays(Duration.ofDays(12), 10_000, MONTH, 10_000, MONTH)).isEqualTo(12);
        // Nothing left: nothing carried over.
        assertThat(extraDays(Duration.ZERO, 10_000, MONTH, 20_000, MONTH)).isZero();
        assertThat(extraDays(Duration.ofDays(-3), 10_000, MONTH, 20_000, MONTH)).isZero();
        // Less than a day of value is dropped.
        assertThat(extraDays(Duration.ofHours(10), 10_000, MONTH, 20_000, MONTH)).isZero();
    }

    @Test
    void extra_days_never_exceed_the_value_paid() {
        for (long remaining = 0; remaining <= 30; remaining++) {
            for (long newAmount : new long[]{10_001, 15_000, 30_000, 299_999}) {
                long days = extraDays(Duration.ofDays(remaining), 10_000, MONTH, newAmount, MONTH);
                // value of the days granted (new price) ≤ value left (old price), in exact integers
                assertThat(days * newAmount).isLessThanOrEqualTo(remaining * 10_000);
            }
        }
    }

    @Test
    void large_amounts_do_not_overflow() {
        assertThat(extraDays(Duration.ofDays(365), 999_999_999_999L, YEAR, 999_999_999_999L, YEAR)).isEqualTo(365);
    }
}
