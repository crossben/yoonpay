package dev.yoonpay.core.ledger;

import java.util.Currency;
import java.util.Objects;

/** One leg of a posting. Positive = debit, negative = credit; never zero. */
public record LedgerEntry(String account, long amount, Currency currency) {

    public LedgerEntry {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(currency, "currency");
        if (account.isBlank()) {
            throw new IllegalArgumentException("account is blank");
        }
        if (amount == 0) {
            throw new IllegalArgumentException("a ledger entry cannot be zero");
        }
    }
}
