package dev.yoonpay.core.ledger;

import dev.yoonpay.core.money.Money;
import org.junit.jupiter.api.Test;

import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerPostingTest {

    private static final Currency XOF = Currency.getInstance("XOF");
    private static final Currency EUR = Currency.getInstance("EUR");

    @Test
    void transfer_produces_a_balanced_two_leg_posting() {
        LedgerPosting p = LedgerPosting.transfer("collect",
                Accounts.providerBalance("paydunya"), Accounts.customerFunds("paydunya"), Money.of(5000, "XOF"));

        assertThat(p.entries()).containsExactly(
                new LedgerEntry("provider_balance:paydunya", 5000, XOF),
                new LedgerEntry("customer_funds:paydunya", -5000, XOF));
    }

    @Test
    void unbalanced_postings_are_rejected() {
        assertThatThrownBy(() -> new LedgerPosting("bad", List.of(
                new LedgerEntry("a", 5000, XOF),
                new LedgerEntry("b", -4999, XOF))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unbalanced");
    }

    @Test
    void each_currency_must_balance_on_its_own() {
        assertThatThrownBy(() -> new LedgerPosting("bad", List.of(
                new LedgerEntry("a", 100, XOF),
                new LedgerEntry("b", -100, EUR))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_posting_needs_two_entries_and_no_zero_legs() {
        assertThatThrownBy(() -> new LedgerPosting("bad", List.of(new LedgerEntry("a", 1, XOF))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LedgerEntry("a", 0, XOF))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
