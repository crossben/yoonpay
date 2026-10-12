package dev.yoonpay.server.billing;

import dev.yoonpay.server.config.YoonProperties;
import dev.yoonpay.server.payment.PaymentRecord;
import dev.yoonpay.server.payment.PaymentRepository;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Subscriptions and invoices as the API (and the events) return them. */
@Component
public class BillingViews {

    public record CustomerRef(String id, String externalId) {
    }

    public record PlanRef(String id, String slug, String name) {
    }

    public record PriceView(String id, String currency, long amount, String interval, int intervalCount,
                            PlanRef plan) {
    }

    public record PendingChange(PriceView price, Instant effectiveAt) {
    }

    public record LineView(String description, String price, long amount) {
    }

    public record InvoiceView(String id, String object, String number, String customer, String subscription,
                              String kind, String status, long amountDue, String currency, List<LineView> lines,
                              Instant periodStart, Instant periodEnd, String payment, String checkoutUrl,
                              String receiptUrl, Instant paidAt, Instant voidedAt, Instant createdAt) {
    }

    public record SubscriptionView(String id, String object, CustomerRef customer, PriceView price, String status,
                                   String collectionMethod, Instant currentPeriodStart, Instant currentPeriodEnd,
                                   int gracePeriodDays, boolean cancelAtPeriodEnd, PendingChange pendingChange,
                                   Instant canceledAt, boolean comp, boolean entitled, JsonNode features,
                                   InvoiceView latestInvoice, JsonNode metadata, Instant trialEnd, Instant createdAt,
                                   Instant updatedAt) {
    }

    private final CustomerRepository customers;
    private final CatalogRepository catalog;
    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final ObjectMapper json;
    private final YoonProperties properties;

    public BillingViews(CustomerRepository customers, CatalogRepository catalog, InvoiceRepository invoices,
                        PaymentRepository payments, ObjectMapper json, YoonProperties properties) {
        this.properties = properties;
        this.customers = customers;
        this.catalog = catalog;
        this.invoices = invoices;
        this.payments = payments;
        this.json = json;
    }

    public SubscriptionView subscription(SubscriptionRecord s) {
        UUID app = s.applicationId();
        CustomerRecord c = customers.find(app, s.customerId()).orElseThrow();
        PriceRecord price = catalog.findPrice(app, s.priceId()).orElseThrow();
        PlanRecord plan = catalog.findPlan(app, price.planId()).orElseThrow();
        PendingChange pending = s.pendingPriceId() == null ? null
                : new PendingChange(price(app, catalog.findPrice(app, s.pendingPriceId()).orElseThrow()), s.currentPeriodEnd());
        dev.yoonpay.core.lifecycle.SubscriptionStatus status = dev.yoonpay.core.lifecycle.SubscriptionStatus.valueOf(s.status());
        return new SubscriptionView(s.id(), "subscription", new CustomerRef(c.id(), c.externalId()), price(app, price),
                lower(s.status()), lower(s.collectionMethod()), s.currentPeriodStart(), s.currentPeriodEnd(),
                s.gracePeriodDays(), s.cancelAtPeriodEnd(), pending, s.canceledAt(), s.comp(), status.isEntitled(),
                Metadata.read(plan.features(), json), invoices.latest(s.id()).map(this::invoice).orElse(null),
                Metadata.read(s.metadata(), json), s.trialEnd(), s.createdAt(), s.updatedAt());
    }

    public InvoiceView invoice(InvoiceRecord i) {
        String checkoutUrl = null;
        if ("OPEN".equals(i.status()) && i.paymentId() != null) {
            PaymentRecord p = payments.find(i.applicationId(), i.paymentId()).orElse(null);
            if (p != null && ("CREATED".equals(p.status()) || "PENDING".equals(p.status()))) {
                checkoutUrl = p.hosted() ? p.hostedCheckoutUrl() : p.checkoutUrl();
            }
        }
        List<LineView> lines = invoices.lines(i.id()).stream()
                .map(l -> new LineView(l.description(), l.priceId(), l.amount())).toList();
        return new InvoiceView(i.id(), "invoice", i.number(), i.customerId(), i.subscriptionId(), lower(i.kind()),
                lower(i.status()), i.amountDue(), i.currency(), lines, i.periodStart(), i.periodEnd(), i.paymentId(),
                checkoutUrl, receiptUrl(i), i.paidAt(), i.voidedAt(), i.createdAt());
    }

    /** The public receipt page (ADR-0027), once paid; relative when no public URL is configured. */
    public String receiptUrl(InvoiceRecord i) {
        if (!"PAID".equals(i.status())) {
            return null;
        }
        String path = receiptPath(i);
        return properties.publicUrl() == null ? path : properties.publicUrl().resolve(path).toString();
    }

    public static String receiptPath(InvoiceRecord i) {
        return "/checkout/receipts/" + i.id() + "?t=" + i.receiptToken();
    }

    private PriceView price(UUID app, PriceRecord p) {
        PlanRecord plan = catalog.findPlan(app, p.planId()).orElseThrow();
        return new PriceView(p.id(), p.currency(), p.amount(), lower(p.interval()), p.intervalCount(),
                new PlanRef(plan.id(), plan.slug(), plan.name()));
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
