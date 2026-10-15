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
                    "reference", "provider", "provider_reference", "customer_phone", "failure_code", "payment_link");
            rows.forEach(p -> row(out, p.id(), p.createdAt(), p.status().toLowerCase(), p.amount(), p.amountRefunded(),
                    p.currency(), p.country(), p.method(), p.reference(), p.provider(), p.providerReference(),
                    Phones.mask(p.customerPhone()), p.failureCode(), p.paymentLinkId()));
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

    record SubscriptionRow(String id, Instant createdAt, String customerId, String externalId, String plan,
                           String priceId, String status, Instant currentPeriodStart, Instant currentPeriodEnd,
                           boolean cancelAtPeriodEnd, Instant canceledAt, boolean comp, long amount, String currency,
                           String interval, int intervalCount, Instant trialEnd, String couponCode,
                           Integer discountPeriodsRemaining) {
    }

    @GetMapping("/v1/exports/subscriptions.csv")
    @Transactional(readOnly = true)
    public void subscriptions(AppPrincipal app, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                              HttpServletResponse response) throws IOException {
        try (PrintWriter out = csv(response, "subscriptions");
             Stream<SubscriptionRow> rows = jdbc.sql("""
                             SELECT s.id, s.created_at, s.customer_id, c.external_id, pl.slug AS plan, s.price_id,
                                    s.status, s.current_period_start, s.current_period_end, s.cancel_at_period_end,
                                    s.canceled_at, s.comp, pr.amount, pr.currency, pr.interval, pr.interval_count,
                                    s.trial_end, co.code AS coupon_code, s.discount_periods_remaining
                             FROM subscriptions s
                             JOIN customers c ON c.id = s.customer_id
                             JOIN prices pr ON pr.id = s.price_id
                             JOIN plans pl ON pl.id = pr.plan_id
                             LEFT JOIN coupons co ON co.id = s.coupon_id
                             WHERE s.application_id = :app
                               AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR s.created_at >= :from)
                               AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR s.created_at < :to)
                             ORDER BY s.id""")
                     .param("app", app.id())
                     .param("from", from == null ? null : Timestamp.from(from))
                     .param("to", to == null ? null : Timestamp.from(to))
                     .query(SubscriptionRow.class).stream()) {
            row(out, "id", "created_at", "customer", "customer_external_id", "plan", "price", "status",
                    "current_period_start", "current_period_end", "cancel_at_period_end", "canceled_at", "comp",
                    "amount", "currency", "interval", "interval_count", "trial_end", "coupon", "discount_periods_remaining");
            rows.forEach(r -> row(out, r.id(), r.createdAt(), r.customerId(), r.externalId(), r.plan(), r.priceId(),
                    r.status().toLowerCase(), r.currentPeriodStart(), r.currentPeriodEnd(), r.cancelAtPeriodEnd(),
                    r.canceledAt(), r.comp(), r.amount(), r.currency(), r.interval().toLowerCase(), r.intervalCount(),
                    r.trialEnd(), r.couponCode(), r.discountPeriodsRemaining()));
        }
    }

    record InvoiceRow(String id, String number, Instant createdAt, String customerId, String subscriptionId,
                      String kind, String status, long amountDue, String currency, Instant periodStart,
                      Instant periodEnd, String paymentId, Instant paidAt, Instant voidedAt, long subtotal,
                      long discountAmount, String couponCode) {
    }

    @GetMapping("/v1/exports/invoices.csv")
    @Transactional(readOnly = true)
    public void invoices(AppPrincipal app, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                         HttpServletResponse response) throws IOException {
        try (PrintWriter out = csv(response, "invoices");
             Stream<InvoiceRow> rows = jdbc.sql("""
                             SELECT i.id, i.number, i.created_at, i.customer_id, i.subscription_id, i.kind, i.status,
                                    i.amount_due, i.currency, i.period_start, i.period_end, i.payment_id, i.paid_at,
                                    i.voided_at, i.subtotal, i.discount_amount, co.code AS coupon_code
                             FROM invoices i LEFT JOIN coupons co ON co.id = i.coupon_id
                             WHERE i.application_id = :app
                               AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR i.created_at >= :from)
                               AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR i.created_at < :to)
                             ORDER BY i.id""")
                     .param("app", app.id())
                     .param("from", from == null ? null : Timestamp.from(from))
                     .param("to", to == null ? null : Timestamp.from(to))
                     .query(InvoiceRow.class).stream()) {
            row(out, "id", "number", "created_at", "customer", "subscription", "kind", "status", "amount_due",
                    "currency", "period_start", "period_end", "payment", "paid_at", "voided_at", "subtotal",
                    "discount_amount", "coupon");
            rows.forEach(r -> row(out, r.id(), r.number(), r.createdAt(), r.customerId(), r.subscriptionId(),
                    r.kind().toLowerCase(), r.status().toLowerCase(), r.amountDue(), r.currency(), r.periodStart(),
                    r.periodEnd(), r.paymentId(), r.paidAt(), r.voidedAt(), r.subtotal(), r.discountAmount(),
                    r.couponCode()));
        }
    }

    record RedemptionRow(String couponId, String code, Instant redeemedAt, String subscriptionId, String customerId,
                         String externalId) {
    }

    /** One row per redemption: which customer and subscription used which coupon, when. */
    @GetMapping("/v1/exports/coupons.csv")
    @Transactional(readOnly = true)
    public void coupons(AppPrincipal app, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                        HttpServletResponse response) throws IOException {
        try (PrintWriter out = csv(response, "coupons");
             Stream<RedemptionRow> rows = jdbc.sql("""
                             SELECT r.coupon_id, co.code, r.created_at AS redeemed_at, r.subscription_id, r.customer_id,
                                    c.external_id
                             FROM coupon_redemptions r
                             JOIN coupons co ON co.id = r.coupon_id
                             JOIN customers c ON c.id = r.customer_id
                             WHERE r.application_id = :app
                               AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR r.created_at >= :from)
                               AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR r.created_at < :to)
                             ORDER BY r.id""")
                     .param("app", app.id())
                     .param("from", from == null ? null : Timestamp.from(from))
                     .param("to", to == null ? null : Timestamp.from(to))
                     .query(RedemptionRow.class).stream()) {
            row(out, "coupon", "code", "redeemed_at", "subscription", "customer", "customer_external_id");
            rows.forEach(r -> row(out, r.couponId(), r.code(), r.redeemedAt(), r.subscriptionId(), r.customerId(),
                    r.externalId()));
        }
    }

    record LinkRow(String id, Instant createdAt, String description, Long amount, Long minAmount, Long maxAmount,
                   String currency, String country, Integer maxPayments, Instant expiresAt, boolean active,
                   long paymentsCount, long amountCollected) {
    }

    /** Every payment link created in the window, with what it collected (succeeded payments). */
    @GetMapping("/v1/exports/payment_links.csv")
    @Transactional(readOnly = true)
    public void paymentLinks(AppPrincipal app, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                             HttpServletResponse response) throws IOException {
        try (PrintWriter out = csv(response, "payment_links");
             Stream<LinkRow> rows = jdbc.sql("""
                             SELECT l.id, l.created_at, l.description, l.amount, l.min_amount, l.max_amount, l.currency,
                                    l.country, l.max_payments, l.expires_at, l.active,
                                    count(p.id) AS payments_count, coalesce(sum(p.amount), 0) AS amount_collected
                             FROM payment_links l
                             LEFT JOIN payments p ON p.payment_link_id = l.id AND p.status = 'SUCCEEDED'
                             WHERE l.application_id = :app
                               AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR l.created_at >= :from)
                               AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR l.created_at < :to)
                             GROUP BY l.id
                             ORDER BY l.id""")
                     .param("app", app.id())
                     .param("from", from == null ? null : Timestamp.from(from))
                     .param("to", to == null ? null : Timestamp.from(to))
                     .query(LinkRow.class).stream()) {
            row(out, "id", "created_at", "description", "amount", "min_amount", "max_amount", "currency", "country",
                    "max_payments", "expires_at", "active", "payments_count", "amount_collected");
            rows.forEach(r -> row(out, r.id(), r.createdAt(), r.description(), r.amount(), r.minAmount(), r.maxAmount(),
                    r.currency(), r.country(), r.maxPayments(), r.expiresAt(), r.active(), r.paymentsCount(),
                    r.amountCollected()));
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
