package dev.yoonpay.core.billing;

/** Coupon arithmetic (ADR-0029): integers only, never below zero, rounding in the merchant's favour. */
public final class Discounts {

    /** How many invoices a coupon discounts. */
    public enum Duration { ONCE, REPEATING, FOREVER }

    public static final int MAX_PERIODS = 120;

    private Discounts() {
    }

    /** Exactly one of {@code percentOff} (1–100) and {@code amountOff} (minor units, &gt; 0). */
    public record Discount(Integer percentOff, Long amountOff) {

        public Discount {
            if ((percentOff == null) == (amountOff == null)) {
                throw new IllegalArgumentException("a discount is either percent_off or amount_off");
            }
            if (percentOff != null && (percentOff < 1 || percentOff > 100)) {
                throw new IllegalArgumentException("percent_off must be 1–100");
            }
            if (amountOff != null && amountOff <= 0) {
                throw new IllegalArgumentException("amount_off must be positive");
            }
        }

        /** What comes off an invoice of {@code subtotal} minor units: at most the subtotal. */
        public long amountOff(long subtotal) {
            if (subtotal < 0) {
                throw new IllegalArgumentException("subtotal must not be negative");
            }
            if (amountOff != null) {
                return Math.min(amountOff, subtotal);
            }
            // floor(subtotal × p / 100) without overflowing: split subtotal into hundreds and the rest.
            return (subtotal / 100) * percentOff + (subtotal % 100) * percentOff / 100;
        }
    }

    /** Invoices left to discount when the coupon is redeemed; {@code null} = forever. */
    public static Integer initialPeriods(Duration duration, Integer durationInPeriods) {
        return switch (duration) {
            case ONCE, FOREVER -> {
                if (durationInPeriods != null) {
                    throw new IllegalArgumentException("duration_in_periods is only for repeating coupons");
                }
                yield duration == Duration.ONCE ? 1 : null;
            }
            case REPEATING -> {
                if (durationInPeriods == null || durationInPeriods < 1 || durationInPeriods > MAX_PERIODS) {
                    throw new IllegalArgumentException("a repeating coupon needs duration_in_periods 1–" + MAX_PERIODS);
                }
                yield durationInPeriods;
            }
        };
    }

    /** Whether a discount with {@code remaining} invoices left still applies. */
    public static boolean applies(Integer remaining) {
        return remaining == null || remaining > 0;
    }

    /** Invoices left after one more was discounted. */
    public static Integer afterInvoice(Integer remaining) {
        return remaining == null ? null : Math.max(0, remaining - 1);
    }
}
