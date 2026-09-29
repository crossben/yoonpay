package dev.yoonpay.server.ledger;

import dev.yoonpay.core.ledger.Accounts;
import dev.yoonpay.core.ledger.LedgerPosting;
import dev.yoonpay.core.money.Money;
import dev.yoonpay.server.PostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Currency;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the zero-sum rule is enforced by Postgres itself: these tests bypass the Java
 * checks with raw SQL, and the database must refuse at commit.
 */
class LedgerDatabaseGuardTest extends PostgresTest {

    private static final Currency XOF = Currency.getInstance("XOF");

    @Autowired
    LedgerRepository ledger;

    @Autowired
    TransactionTemplate tx;

    @Test
    void a_balanced_posting_commits_and_moves_balances() {
        UUID app = newApplication();

        ledger.post(app, LedgerPosting.transfer("collect order_1042",
                Accounts.providerBalance("paydunya"), Accounts.customerFunds("paydunya"), Money.of(5000, "XOF")));

        assertThat(ledger.balance(app, "provider_balance:paydunya", XOF)).isEqualTo(5000);
        assertThat(ledger.balance(app, "customer_funds:paydunya", XOF)).isEqualTo(-5000);
    }

    @Test
    void postgres_rejects_an_unbalanced_posting() {
        UUID app = newApplication();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            long posting = insertPosting(app);
            insertEntry(posting, "provider_balance:paydunya", "XOF", 5000);
            insertEntry(posting, "customer_funds:paydunya", "XOF", -4999);
        }))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("does not balance in XOF");

        assertThat(postingCount(app)).isZero();
    }

    @Test
    void postgres_rejects_a_posting_that_balances_only_across_currencies() {
        UUID app = newApplication();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            long posting = insertPosting(app);
            insertEntry(posting, "a", "XOF", 100);
            insertEntry(posting, "b", "EUR", -100);
        })).isInstanceOf(DataAccessException.class).hasMessageContaining("does not balance");
    }

    @Test
    void postgres_rejects_a_posting_with_a_single_entry() {
        UUID app = newApplication();

        assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                insertEntry(insertPosting(app), "a", "XOF", 100)))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("needs at least 2");
    }

    @Test
    void postgres_rejects_a_posting_with_no_entries() {
        UUID app = newApplication();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> insertPosting(app)))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("has no entries");
    }

    @Test
    void the_ledger_is_append_only() {
        UUID app = newApplication();
        long posting = ledger.post(app, LedgerPosting.transfer("x", "a", "b", Money.of(10, "XOF")));

        assertThatThrownBy(() -> jdbc.sql("UPDATE ledger_entries SET amount = 1 WHERE posting_id = :p")
                .param("p", posting).update())
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM ledger_postings WHERE id = :p")
                .param("p", posting).update())
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("append-only");
    }

    @Test
    void a_posting_rolls_back_with_the_transaction_it_belongs_to() {
        UUID app = newApplication();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            ledger.post(app, LedgerPosting.transfer("x", "a", "b", Money.of(10, "XOF")));
            throw new IllegalStateException("state change failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(postingCount(app)).isZero();
    }

    private long insertPosting(UUID app) {
        return jdbc.sql("INSERT INTO ledger_postings (application_id, description) VALUES (:a, 'raw') RETURNING id")
                .param("a", app).query(Long.class).single();
    }

    private void insertEntry(long posting, String account, String currency, long amount) {
        jdbc.sql("INSERT INTO ledger_entries (posting_id, account, currency, amount) VALUES (:p, :acc, :c, :amt)")
                .param("p", posting).param("acc", account).param("c", currency).param("amt", amount).update();
    }

    private int postingCount(UUID app) {
        return jdbc.sql("SELECT count(*) FROM ledger_postings WHERE application_id = :a")
                .param("a", app).query(Integer.class).single();
    }
}
