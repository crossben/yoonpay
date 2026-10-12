package dev.yoonpay.server.billing;

import dev.yoonpay.core.billing.BillingPeriods;
import dev.yoonpay.core.billing.Stacking;
import dev.yoonpay.core.lifecycle.InvoiceStatus;
import dev.yoonpay.core.lifecycle.SubscriptionStatus;
import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.lifecycle.Alerts;
import dev.yoonpay.server.lifecycle.StatusEvents.Cause;
import dev.yoonpay.server.payment.PaymentRecord;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * What a confirmed payment does to billing (ADR-0026): marks its invoice paid and adds the paid
 * period to the subscription. Called by {@code PaymentTransitions} inside the transaction that
 * makes the payment SUCCEEDED, so the payment, the invoice and the subscription change together
 * or not at all. Settlement has already checked that the amount matches.
 */
@Component
public class BillingPayments {

    private final InvoiceRepository invoices;
    private final SubscriptionRepository subscriptions;
    private final CatalogRepository catalog;
    private final InvoiceTransitions invoiceTransitions;
    private final SubscriptionTransitions transitions;
    private final Alerts alerts;
    private final Discounting discounting;
    private final Clock clock;

    public BillingPayments(InvoiceRepository invoices, SubscriptionRepository subscriptions, CatalogRepository catalog,
                           InvoiceTransitions invoiceTransitions, SubscriptionTransitions transitions, Alerts alerts,
                           Discounting discounting, Clock clock) {
        this.discounting = discounting;
        this.invoices = invoices;
        this.subscriptions = subscriptions;
        this.catalog = catalog;
        this.invoiceTransitions = invoiceTransitions;
        this.transitions = transitions;
        this.alerts = alerts;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void paymentSucceeded(AppPrincipal app, PaymentRecord payment) {
        String invoiceId = invoices.invoiceOfPayment(payment.id()).orElse(null);
        if (invoiceId == null) {
            return; // an ordinary one-off payment
        }
        // Billing lock order everywhere: subscription, then invoice (renew and change do the same).
        String subscriptionId = invoices.find(app.id(), invoiceId).orElseThrow().subscriptionId();
        SubscriptionRecord s = subscriptions.lock(app.id(), subscriptionId).orElseThrow();
        InvoiceRecord invoice = invoices.lock(app.id(), invoiceId).orElseThrow();
        if (InvoiceStatus.PAID.name().equals(invoice.status())) {
            return; // already paid (by an earlier payment of the same invoice): never two periods for one invoice
        }
        if (InvoiceStatus.VOID.name().equals(invoice.status())) {
            alerts.raise(Alerts.Type.late_invoice_payment, invoice.id(), "payment " + payment.id() + " for a voided invoice; applied");
        }
        settle(app, s, invoice, Cause.webhook, "paid by " + payment.id());
    }

    /**
     * An invoice with nothing to pay (a 100% coupon, ADR-0029): paid at creation, without a
     * payment, in the creating transaction. No ledger posting: no money moved.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void paidWithoutPayment(AppPrincipal app, String subscriptionId, String invoiceId, Cause cause) {
        SubscriptionRecord s = subscriptions.lock(app.id(), subscriptionId).orElseThrow();
        InvoiceRecord invoice = invoices.lock(app.id(), invoiceId).orElseThrow();
        if (invoice.amountDue() != 0 || !InvoiceStatus.OPEN.name().equals(invoice.status())) {
            throw new IllegalStateException("only an open invoice with nothing to pay is paid without a payment");
        }
        settle(app, s, invoice, cause, "nothing to pay (coupon)");
    }

    /** Marks the locked invoice paid and adds its period to the locked subscription. */
    private void settle(AppPrincipal app, SubscriptionRecord s, InvoiceRecord invoice, Cause cause, String paidBy) {
        Instant paidAt = clock.instant();
        SubscriptionStatus status = SubscriptionStatus.valueOf(s.status());
        PriceRecord price = catalog.findPrice(app.id(), invoice.priceId()).orElseThrow();

        Instant start;
        Instant end;
        int anchor;
        if ("UPGRADE".equals(invoice.kind())) {
            // A fresh period from now, plus the unused time of the old one converted into whole days.
            start = paidAt;
            anchor = BillingPeriods.anchorOf(start);
            end = BillingPeriods.periodEnd(start, price.intervalEnum(), price.intervalCount(), anchor);
            long extra = 0;
            if (status.isEntitled() && s.currentPeriodEnd() != null && s.currentPeriodStart() != null
                    && s.currentPeriodEnd().isAfter(paidAt)) {
                PriceRecord old = catalog.findPrice(app.id(), s.priceId()).orElseThrow();
                extra = BillingPeriods.extraDays(Duration.between(paidAt, s.currentPeriodEnd()), old.amount(),
                        Duration.between(s.currentPeriodStart(), s.currentPeriodEnd()), price.amount(),
                        Duration.between(start, end));
            }
            end = end.plus(Duration.ofDays(extra));
        } else {
            start = Stacking.nextPeriodStart(status, s.currentPeriodEnd(), paidAt);
            boolean fresh = start.equals(paidAt) || s.billingAnchor() == null;
            anchor = fresh ? BillingPeriods.anchorOf(start) : s.billingAnchor();
            end = BillingPeriods.periodEnd(start, price.intervalEnum(), price.intervalCount(), anchor);
        }

        Map<String, Object> invoiceFields = new HashMap<>();
        invoiceFields.put("paid_at", paidAt);
        invoiceFields.put("period_start", start);
        invoiceFields.put("period_end", end);
        invoiceTransitions.apply(app, invoice.id(), InvoiceStatus.PAID, cause, paidBy, invoiceFields);

        Map<String, Object> fields = new HashMap<>();
        fields.put("current_period_start", start);
        fields.put("current_period_end", end);
        fields.put("billing_anchor", anchor);
        fields.put("cancel_at_period_end", false); // paying again means the customer wants to continue
        fields.put("canceled_at", null);
        fields.put("comp", false); // a paid period: no longer operator-granted
        fields.putAll(discounting.consume(s, invoice));
        boolean priceChanged = !invoice.priceId().equals(s.priceId());
        if (priceChanged || invoice.priceId().equals(s.pendingPriceId())) {
            fields.put("price_id", invoice.priceId());
            fields.put("pending_price_id", null);
        }
        String detail = invoice.kind().toLowerCase(java.util.Locale.ROOT) + " invoice " + invoice.number() + " paid";
        if (status == SubscriptionStatus.ACTIVE) {
            transitions.update(app, s.id(), cause, detail, fields,
                    priceChanged ? "subscription.changed" : "subscription.renewed");
        } else {
            transitions.apply(app, s.id(), SubscriptionStatus.ACTIVE, cause, detail, fields);
        }
    }
}
