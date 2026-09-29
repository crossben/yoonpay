package dev.yoonpay.core.money;

import java.util.Currency;
import java.util.Objects;

/**
 * An amount in the currency's minor units. XOF has no minor unit, so
 * 5 000 XOF is {@code Money.of(5000, "XOF")}. Never negative: direction is carried by
 * the operation (collect, refund, payout) or by ledger entry signs, not by the amount.
 */
public record Money(long amount, Currency currency) {

    public Money {
        Objects.requireNonNull(currency, "currency");
        if (amount < 0) {
            throw new IllegalArgumentException("Money cannot be negative: " + amount);
        }
    }

    public static Money of(long amount, String currencyCode) {
        return new Money(amount, Currency.getInstance(currencyCode));
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(amount, other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(amount, other.amount), currency);
    }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return amount > other.amount;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch: " + currency + " vs " + other.currency);
        }
    }

    @Override
    public String toString() {
        return amount + " " + currency.getCurrencyCode();
    }
}
