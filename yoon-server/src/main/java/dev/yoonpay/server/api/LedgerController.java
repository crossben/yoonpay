package dev.yoonpay.server.api;

import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.web.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * The shadow ledger, read-only. It mirrors what Yoon believes each of the application's
 * provider accounts holds; the provider's own statement is the authority.
 */
@RestController
public class LedgerController {

    private final JdbcClient jdbc;

    public LedgerController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Balance(String account, String provider, String currency, long amount) {
    }

    public record Balances(String object, String note, List<Balance> data) {
    }

    public record Entry(long id, long postingId, String description, String account, String currency, long amount,
                        Instant createdAt) {
    }

    @GetMapping("/v1/balances")
    Balances balances(AppPrincipal app) {
        List<Balance> data = jdbc.sql("""
                        SELECT e.account, split_part(e.account, ':', 2) AS provider, e.currency, sum(e.amount) AS amount
                        FROM ledger_entries e JOIN ledger_postings p ON p.id = e.posting_id
                        WHERE p.application_id = :app AND e.account NOT LIKE 'customer_funds:%'
                        GROUP BY e.account, e.currency
                        ORDER BY e.account, e.currency""")
                .param("app", app.id()).query(Balance.class).list();
        return new Balances("balances",
                "Yoon's view from its own ledger, not the provider's statement. Reconcile against your provider dashboards.",
                data);
    }

    @GetMapping("/v1/ledger/entries")
    Page<Entry> entries(AppPrincipal app,
                        @RequestParam(required = false) String provider,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                        @RequestParam(name = "starting_after", required = false) Long startingAfter,
                        @RequestParam(required = false) Integer limit) {
        int n = Page.limit(limit);
        List<Entry> rows = jdbc.sql("""
                        SELECT e.id, e.posting_id, p.description, e.account, e.currency, e.amount, p.created_at
                        FROM ledger_entries e JOIN ledger_postings p ON p.id = e.posting_id
                        WHERE p.application_id = :app
                          AND (CAST(:provider AS TEXT) IS NULL OR split_part(e.account, ':', 2) = :provider)
                          AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR p.created_at >= :from)
                          AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR p.created_at < :to)
                          AND (CAST(:cursor AS BIGINT) IS NULL OR e.id < :cursor)
                        ORDER BY e.id DESC LIMIT :limit""")
                .param("app", app.id()).param("provider", provider)
                .param("from", from == null ? null : Timestamp.from(from))
                .param("to", to == null ? null : Timestamp.from(to))
                .param("cursor", startingAfter).param("limit", n + 1)
                .query(Entry.class).list();
        return Page.of(rows, n, e -> Long.toString(e.id()));
    }
}
