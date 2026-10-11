package dev.yoonpay.core.billing;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Billing period arithmetic (ADR-0026). Pure functions on UTC instants; no clock, no money types.
 *
 * <p>Monthly and yearly periods are anchored on a day of the month: a subscription anchored on the
 * 31st ends on 28/29 February, then on 31 March again. Time of day is kept.
 */
public final class BillingPeriods {

    private BillingPeriods() {
    }

    /**
     * The end of a period that starts at {@code start}.
     *
     * @param anchorDay day of the month (1–31) monthly and yearly periods return to; ignored for
     *                  DAY and WEEK
     */
    public static Instant periodEnd(Instant start, Interval interval, int count, int anchorDay) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(interval, "interval");
        if (count < 1) {
            throw new IllegalArgumentException("interval count must be at least 1");
        }
        if (anchorDay < 1 || anchorDay > 31) {
            throw new IllegalArgumentException("anchor day must be 1–31");
        }
        LocalDateTime s = LocalDateTime.ofInstant(start, ZoneOffset.UTC);
        LocalDateTime end = switch (interval) {
            case DAY -> s.plusDays(count);
            case WEEK -> s.plusWeeks(count);
            case MONTH -> anchored(s.plusMonths(count), anchorDay);
            case YEAR -> anchored(s.plusYears(count), anchorDay);
        };
        return end.toInstant(ZoneOffset.UTC);
    }

    /** The day of the month of {@code start}, used as the anchor of a new subscription. */
    public static int anchorOf(Instant start) {
        return LocalDateTime.ofInstant(start, ZoneOffset.UTC).getDayOfMonth();
    }

    private static LocalDateTime anchored(LocalDateTime t, int anchorDay) {
        int last = t.toLocalDate().lengthOfMonth();
        return t.withDayOfMonth(Math.min(anchorDay, last));
    }

    /**
     * Whether moving from the old price to the new one costs more per unit of time, compared on
     * the two periods' actual lengths with exact integer arithmetic. Equal is not an upgrade.
     */
    public static boolean isUpgrade(long oldAmount, Duration oldPeriod, long newAmount, Duration newPeriod) {
        positive(oldAmount, oldPeriod, newAmount, newPeriod);
        // new/newLen > old/oldLen  ⇔  new × oldLen > old × newLen
        BigInteger lhs = BigInteger.valueOf(newAmount).multiply(BigInteger.valueOf(oldPeriod.toSeconds()));
        BigInteger rhs = BigInteger.valueOf(oldAmount).multiply(BigInteger.valueOf(newPeriod.toSeconds()));
        return lhs.compareTo(rhs) > 0;
    }

    /**
     * Whole days of the new price worth the unused part of the old period (ADR-0026, upgrades):
     * {@code floor(remaining × oldAmount/oldPeriod ÷ (newAmount/newPeriod) ÷ 1 day)}. Rounded down,
     * so the customer never gets more than they paid for. Zero when nothing remains.
     */
    public static long extraDays(Duration remaining, long oldAmount, Duration oldPeriod,
                                 long newAmount, Duration newPeriod) {
        positive(oldAmount, oldPeriod, newAmount, newPeriod);
        if (remaining.isNegative() || remaining.isZero()) {
            return 0;
        }
        BigInteger numerator = BigInteger.valueOf(remaining.toSeconds())
                .multiply(BigInteger.valueOf(oldAmount))
                .multiply(BigInteger.valueOf(newPeriod.toSeconds()));
        BigInteger denominator = BigInteger.valueOf(oldPeriod.toSeconds())
                .multiply(BigInteger.valueOf(newAmount))
                .multiply(BigInteger.valueOf(Duration.ofDays(1).toSeconds()));
        return numerator.divide(denominator).longValueExact();
    }

    private static void positive(long oldAmount, Duration oldPeriod, long newAmount, Duration newPeriod) {
        if (oldAmount <= 0 || newAmount <= 0) {
            throw new IllegalArgumentException("amounts must be positive");
        }
        if (oldPeriod.toSeconds() <= 0 || newPeriod.toSeconds() <= 0) {
            throw new IllegalArgumentException("periods must be positive");
        }
    }
}
