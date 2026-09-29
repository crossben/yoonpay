package dev.yoonpay.server.api;

import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.payment.PaymentRecord;
import dev.yoonpay.server.payment.PaymentRepository;
import dev.yoonpay.server.payout.PayoutRecord;
import dev.yoonpay.server.payout.PayoutRepository;
import dev.yoonpay.server.phone.Phones;
import dev.yoonpay.server.refund.RefundRecord;
import dev.yoonpay.server.refund.RefundRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.stream.Stream;

/**
 * Streamed CSV exports for accounting. Amounts are integers in minor units, timestamps UTC
 * ISO-8601, phone numbers masked.
 */
@RestController
public class ExportController {

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final PayoutRepository payouts;
    private final JdbcClient jdbc;

    public ExportController(PaymentRepository payments, RefundRepository refunds, PayoutRepository payouts, JdbcClient jdbc) {
        this.payments = payments;
        this.refunds = refunds;
        this.payouts = payouts;
        this.jdbc = jdbc;
    }

    @GetMapping("/v1/exports/payments.csv")
    @Transactional(readOnly = true)
    public void payments(AppPrincipal app, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                         HttpServletResponse response) throws IOException {
        try (PrintWriter out = csv(response, "payments");
             Stream<PaymentRecord> rows = payments.stream(app.id(), from, to)) {
            row(out, "id", "created_at", "status", "amount", "amount_refunded", "currency", "country", "method",
                    "reference", "provider", "provider_reference", "customer_phone", "failure_code");
            rows.forEach(p -> row(out, p.id(), p.createdAt(), p.status().toLowerCase(), p.amount(), p.amountRefunded(),
                    p.currency(), p.country(), p.method(), p.reference(), p.provider(), p.providerReference(),
                    Phones.mask(p.customerPhone()), p.failureCode()));
        }
    }

    @GetMapping("/v1/exports/refunds.csv")
    @Transactional(readOnly = true)
    public void refunds(AppPrincipal app, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                        HttpServletResponse response) throws IOException {
        try (PrintWriter out = csv(response, "refunds");
             Stream<RefundRecord> rows = refunds.stream(app.id(), from, to)) {
            row(out, "id", "created_at", "payment_id", "status", "amount", "currency", "provider", "provider_reference",
                    "reason", "failure_code");
            rows.forEach(r -> row(out, r.id(), r.createdAt(), r.paymentId(), r.status().toLowerCase(), r.amount(),
                    r.currency(), r.provider(), r.providerReference(), r.reason(), r.failureCode()));
        }
    }

    @GetMapping("/v1/exports/payouts.csv")
    @Transactional(readOnly = true)
    public void payouts(AppPrincipal app, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                        HttpServletResponse response) throws IOException {
        try (PrintWriter out = csv(response, "payouts");
             Stream<PayoutRecord> rows = payouts.stream(app.id(), from, to)) {
            row(out, "id", "created_at", "status", "amount", "currency", "country", "method", "reference", "provider",
                    "provider_reference", "recipient_phone", "needs_review", "failure_code");
            rows.forEach(p -> row(out, p.id(), p.createdAt(), p.status().toLowerCase(), p.amount(), p.currency(),
                    p.country(), p.method(), p.reference(), p.provider(), p.providerReference(),
                    Phones.mask(p.recipientPhone()), p.needsReview(), p.failureCode()));
        }
    }

    record LedgerRow(long postingId, Instant createdAt, String description, String account, String currency, long amount) {
    }

    @GetMapping("/v1/exports/ledger.csv")
    @Transactional(readOnly = true)
    public void ledger(AppPrincipal app, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                       HttpServletResponse response) throws IOException {
        try (PrintWriter out = csv(response, "ledger");
             Stream<LedgerRow> rows = jdbc.sql("""
                             SELECT p.id AS posting_id, p.created_at, p.description, e.account, e.currency, e.amount
                             FROM ledger_entries e JOIN ledger_postings p ON p.id = e.posting_id
                             WHERE p.application_id = :app
                               AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR p.created_at >= :from)
                               AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR p.created_at < :to)
                             ORDER BY e.id""")
                     .param("app", app.id())
                     .param("from", from == null ? null : Timestamp.from(from))
                     .param("to", to == null ? null : Timestamp.from(to))
                     .query(LedgerRow.class).stream()) {
            row(out, "posting_id", "created_at", "description", "account", "currency", "amount");
            rows.forEach(r -> row(out, r.postingId(), r.createdAt(), r.description(), r.account(), r.currency(), r.amount()));
        }
    }

    private static PrintWriter csv(HttpServletResponse response, String name) throws IOException {
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + name + ".csv\"");
        return response.getWriter();
    }

    private static void row(PrintWriter out, Object... values) {
        out.print(String.join(",", Arrays.stream(values).map(ExportController::cell).toList()));
        out.print("\r\n");
    }

    /** RFC 4180 quoting, plus a guard against spreadsheet formula injection. */
    static String cell(Object value) {
        if (value == null) {
            return "";
        }
        String s = value.toString();
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 && !(value instanceof Number)) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
