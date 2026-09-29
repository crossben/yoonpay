package dev.yoonpay.core.ledger;

import dev.yoonpay.core.money.Money;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A balanced set of ledger entries. Balance is checked here for early
 * feedback, and again by Postgres at commit — the database is the real guard.
 */
public record LedgerPosting(String description, List<LedgerEntry> entries) {

    public LedgerPosting {
        Objects.requireNonNull(description, "description");
        entries = List.copyOf(entries);
        if (entries.size() < 2) {
            throw new IllegalArgumentException("a posting needs at least two entries");
        }
        Map<String, Long> sums = new HashMap<>();
        for (LedgerEntry e : entries) {
            sums.merge(e.currency().getCurrencyCode(), e.amount(), Math::addExact);
        }
        sums.forEach((currency, sum) -> {
            if (sum != 0) {
                throw new IllegalArgumentException("unbalanced posting in " + currency + ": sum " + sum);
            }
        });
    }

    /** The common two-leg case: move {@code amount} from {@code credit} to {@code debit}. */
    public static LedgerPosting transfer(String description, String debit, String credit, Money amount) {
        return new LedgerPosting(description, List.of(
                new LedgerEntry(debit, amount.amount(), amount.currency()),
                new LedgerEntry(credit, Math.negateExact(amount.amount()), amount.currency())));
    }
}
