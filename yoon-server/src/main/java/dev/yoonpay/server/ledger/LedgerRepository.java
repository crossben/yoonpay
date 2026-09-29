package dev.yoonpay.server.ledger;

import dev.yoonpay.core.ledger.LedgerEntry;
import dev.yoonpay.core.ledger.LedgerPosting;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Currency;
import java.util.UUID;

/** Writes postings to the append-only ledger. Postgres re-checks the balance at commit. */
@Repository
public class LedgerRepository {

    private final JdbcClient jdbc;

    public LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Joins the caller's transaction, so a posting commits together with the state change behind it. */
    @Transactional
    public long post(UUID applicationId, LedgerPosting posting) {
        long postingId = jdbc.sql("""
                        INSERT INTO ledger_postings (application_id, description)
                        VALUES (:app, :description)
                        RETURNING id""")
                .param("app", applicationId)
                .param("description", posting.description())
                .query(Long.class)
                .single();

        for (LedgerEntry entry : posting.entries()) {
            jdbc.sql("""
                            INSERT INTO ledger_entries (posting_id, account, currency, amount)
                            VALUES (:posting, :account, :currency, :amount)""")
                    .param("posting", postingId)
                    .param("account", entry.account())
                    .param("currency", entry.currency().getCurrencyCode())
                    .param("amount", entry.amount())
                    .update();
        }
        return postingId;
    }

    /** Sum of an account from the ledger itself (the source of truth for cached balances). */
    public long balance(UUID applicationId, String account, Currency currency) {
        return jdbc.sql("""
                        SELECT coalesce(sum(e.amount), 0)
                        FROM ledger_entries e
                        JOIN ledger_postings p ON p.id = e.posting_id
                        WHERE p.application_id = :app AND e.account = :account AND e.currency = :currency""")
                .param("app", applicationId)
                .param("account", account)
                .param("currency", currency.getCurrencyCode())
                .query(Long.class)
                .single();
    }
}
