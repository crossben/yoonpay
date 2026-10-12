package dev.yoonpay.server.billing;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class InvoiceRepository {

    public record Line(String description, String priceId, long amount) {
    }

    private final JdbcClient jdbc;

    public InvoiceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The next invoice number for the application, gap-free: the counter row is locked until the
     * calling transaction ends, so concurrent creations wait their turn and a rolled-back
     * creation gives its number back. Must run inside the transaction that inserts the invoice.
     */
    public String nextNumber(UUID app, String prefix, int year) {
        jdbc.sql("INSERT INTO invoice_counters (application_id, year, last_number) VALUES (:app, :year, 0) ON CONFLICT DO NOTHING")
                .param("app", app).param("year", year).update();
        var counter = jdbc.sql("SELECT year, last_number FROM invoice_counters WHERE application_id = :app FOR UPDATE")
                .param("app", app).query((rs, i) -> new long[]{rs.getInt(1), rs.getLong(2)}).single();
        long next = counter[0] == year ? counter[1] + 1 : 1;
        jdbc.sql("UPDATE invoice_counters SET year = :year, last_number = :n WHERE application_id = :app")
                .param("app", app).param("year", year).param("n", next).update();
        return "%s-%d-%06d".formatted(prefix, year, next);
    }

    public void insert(InvoiceRecord i, List<Line> lines) {
        jdbc.sql("""
                        INSERT INTO invoices (id, application_id, number, customer_id, subscription_id, price_id, kind,
                                              status, subtotal, discount_amount, coupon_id, amount_due, currency,
                                              receipt_token)
                        VALUES (:id, :app, :number, :customer, :subscription, :price, :kind, :status, :subtotal, :discount,
                                :coupon, :amount, :currency, :token)""")
                .param("subtotal", i.subtotal()).param("discount", i.discountAmount()).param("coupon", i.couponId())
                .param("id", i.id()).param("app", i.applicationId()).param("number", i.number())
                .param("customer", i.customerId()).param("subscription", i.subscriptionId()).param("price", i.priceId())
                .param("kind", i.kind()).param("status", i.status()).param("amount", i.amountDue())
                .param("currency", i.currency()).param("token", i.receiptToken())
                .update();
        for (Line l : lines) {
            jdbc.sql("INSERT INTO invoice_lines (invoice_id, description, price_id, amount) VALUES (:invoice, :d, :price, :amount)")
                    .param("invoice", i.id()).param("d", l.description()).param("price", l.priceId())
                    .param("amount", l.amount()).update();
        }
    }

    public Optional<InvoiceRecord> find(UUID app, String id) {
        return jdbc.sql("SELECT * FROM invoices WHERE application_id = :app AND id = :id")
                .param("app", app).param("id", id).query(InvoiceRecord.class).optional();
    }

    /** Unscoped: only for the public receipt page, which then checks the receipt token. */
    public Optional<InvoiceRecord> findForReceipt(String id) {
        return jdbc.sql("SELECT * FROM invoices WHERE id = :id").param("id", id).query(InvoiceRecord.class).optional();
    }

    public Optional<InvoiceRecord> lock(UUID app, String id) {
        return jdbc.sql("SELECT * FROM invoices WHERE application_id = :app AND id = :id FOR UPDATE")
                .param("app", app).param("id", id).query(InvoiceRecord.class).optional();
    }

    /** The subscription's open invoice, locked, if there is one (at most one: unique index). */
    public Optional<InvoiceRecord> lockOpen(String subscriptionId) {
        return jdbc.sql("SELECT * FROM invoices WHERE subscription_id = :s AND status = 'OPEN' FOR UPDATE")
                .param("s", subscriptionId).query(InvoiceRecord.class).optional();
    }

    public Optional<InvoiceRecord> latest(String subscriptionId) {
        return jdbc.sql("SELECT * FROM invoices WHERE subscription_id = :s ORDER BY id DESC LIMIT 1")
                .param("s", subscriptionId).query(InvoiceRecord.class).optional();
    }

    /** The invoice a payment pays, if any. */
    public Optional<String> invoiceOfPayment(String paymentId) {
        return jdbc.sql("SELECT invoice_id FROM payments WHERE id = :p AND invoice_id IS NOT NULL")
                .param("p", paymentId).query(String.class).optional();
    }

    public void linkPayment(String invoiceId, String paymentId, String subscriptionId, String customerId) {
        jdbc.sql("UPDATE invoices SET payment_id = :p, updated_at = now() WHERE id = :i")
                .param("i", invoiceId).param("p", paymentId).update();
        jdbc.sql("UPDATE payments SET invoice_id = :i, subscription_id = :s, customer_id = :c WHERE id = :p")
                .param("i", invoiceId).param("s", subscriptionId).param("c", customerId).param("p", paymentId).update();
    }

    public void update(String id, Map<String, Object> fields) {
        StringBuilder sql = new StringBuilder("UPDATE invoices SET updated_at = now()");
        fields.keySet().forEach(k -> sql.append(", ").append(k).append(" = :").append(k));
        var spec = jdbc.sql(sql + " WHERE id = :id").param("id", id);
        for (var e : fields.entrySet()) {
            spec = spec.param(e.getKey(), e.getValue() instanceof Instant t ? java.sql.Timestamp.from(t) : e.getValue());
        }
        spec.update();
    }

    public List<Line> lines(String invoiceId) {
        return jdbc.sql("SELECT description, price_id, amount FROM invoice_lines WHERE invoice_id = :i ORDER BY id")
                .param("i", invoiceId).query(Line.class).list();
    }

    public List<InvoiceRecord> list(UUID app, String customerId, String subscriptionId, String status,
                                    String startingAfter, int limit) {
        return jdbc.sql("""
                        SELECT * FROM invoices
                        WHERE application_id = :app
                          AND (CAST(:customer AS TEXT) IS NULL OR customer_id = :customer)
                          AND (CAST(:subscription AS TEXT) IS NULL OR subscription_id = :subscription)
                          AND (CAST(:status AS TEXT) IS NULL OR status = :status)
                          AND (CAST(:cursor AS TEXT) IS NULL OR id < :cursor)
                        ORDER BY id DESC LIMIT :limit""")
                .param("app", app).param("customer", customerId).param("subscription", subscriptionId)
                .param("status", status).param("cursor", startingAfter).param("limit", limit + 1)
                .query(InvoiceRecord.class).list();
    }
}
