package dev.yoonpay.server.billing;

import dev.yoonpay.core.billing.BillingPeriods;
import dev.yoonpay.core.billing.Stacking;
import dev.yoonpay.core.lifecycle.InvoiceStatus;
import dev.yoonpay.core.lifecycle.SubscriptionStatus;
import dev.yoonpay.core.id.Ids;
import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.config.YoonProperties;
import dev.yoonpay.server.lifecycle.StatusEvents.Cause;
import dev.yoonpay.server.web.ApiProblem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Operator-granted periods (ADR-0026, "Operator"): a subscription becomes active for some periods,
 * or without an end date ({@code open}), with <b>no payment</b> and no ledger posting. Recorded in
 * the subscription's history with cause {@code admin} and the operator's note.
 */
@Service
public class CompService {

    public static final int MAX_PERIODS = 120;

    private final SubscriptionRepository subscriptions;
    private final InvoiceRepository invoices;
    private final CustomerRepository customers;
    private final CatalogRepository catalog;
    private final SubscriptionTransitions transitions;
    private final InvoiceTransitions invoiceTransitions;
    private final YoonProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    public CompService(SubscriptionRepository subscriptions, InvoiceRepository invoices, CustomerRepository customers,
                       CatalogRepository catalog, SubscriptionTransitions transitions,
                       InvoiceTransitions invoiceTransitions, YoonProperties properties, TransactionTemplate tx,
                       Clock clock) {
        this.subscriptions = subscriptions;
        this.invoices = invoices;
        this.customers = customers;
        this.catalog = catalog;
        this.transitions = transitions;
        this.invoiceTransitions = invoiceTransitions;
        this.properties = properties;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * @param target  a subscription id, or a customer id (a new subscription is created for them)
     * @param periods how many periods of {@code priceId} to grant
     * @param until   or: the end of the granted time (e.g. what the customer had already paid for
     *                elsewhere, when migrating); both null: an open-ended comp
     */
    public SubscriptionRecord comp(AppPrincipal app, String target, String priceId, Integer periods, Instant until,
                                   String note) {
        if (periods != null && until != null) {
            throw ApiProblem.invalid("Give periods or until, not both");
        }
        if (periods != null && (periods < 1 || periods > MAX_PERIODS)) {
            throw ApiProblem.invalid("periods must be 1–" + MAX_PERIODS + ", or open");
        }
        if (note == null || note.isBlank() || note.length() > 500) {
            throw ApiProblem.invalid("note is required (who asked and why), up to 500 characters");
        }
        PriceRecord price = catalog.findPrice(app.id(), priceId)
                .orElseThrow(() -> ApiProblem.unprocessable("price_not_found", "No price " + priceId));
        String detail = "comp " + (periods != null ? periods + " period(s)" : until != null ? "until " + until : "open-ended")
                + ": " + note.strip();

        // One transaction: a comp for a customer never leaves a new, un-comped subscription behind.
        String compedId = tx.execute(t -> {
            String id = target.startsWith("cus_") ? newSubscription(app, target, price) : target;
            // Billing lock order: subscription, then invoice.
            SubscriptionRecord s = subscriptions.lock(app.id(), id).orElseThrow(() -> ApiProblem.notFound("Subscription", id));
            invoices.lockOpen(id).ifPresent(i -> invoiceTransitions.apply(app, i.id(), InvoiceStatus.VOID, Cause.admin,
                    "replaced by a comp", Map.of("voided_at", clock.instant())));

            Instant now = clock.instant();
            SubscriptionStatus status = SubscriptionStatus.valueOf(s.status());
            Instant start = Stacking.nextPeriodStart(status, s.currentPeriodEnd(), now);
            Map<String, Object> fields = new HashMap<>();
            if (until != null) {
                if (!until.isAfter(start)) {
                    throw ApiProblem.unprocessable("comp_until_too_early", "until must be after " + start
                            + (start.equals(now) ? " (now)" : " (the end of the period already paid)"));
                }
                fields.put("current_period_end", until);
                fields.put("billing_anchor", BillingPeriods.anchorOf(until));   // later renewals fall on that day
            } else if (periods == null) {
                fields.put("current_period_end", null);   // open: the sweep never ends it
                fields.put("billing_anchor", null);
            } else {
                boolean fresh = start.equals(now) || s.billingAnchor() == null;
                int anchor = fresh ? BillingPeriods.anchorOf(start) : s.billingAnchor();
                Instant end = start;
                for (int n = 0; n < periods; n++) {
                    end = BillingPeriods.periodEnd(end, price.intervalEnum(), price.intervalCount(), anchor);
                }
                fields.put("current_period_end", end);
                fields.put("billing_anchor", anchor);
            }
            fields.put("current_period_start", start);
            fields.put("price_id", price.id());
            fields.put("pending_price_id", null);
            fields.put("cancel_at_period_end", false);
            fields.put("canceled_at", null);
            fields.put("comp", true);
            if (status == SubscriptionStatus.ACTIVE) {
                transitions.update(app, id, Cause.admin, detail, fields, "subscription.renewed");
            } else {
                transitions.apply(app, id, SubscriptionStatus.ACTIVE, Cause.admin, detail, fields);
            }
            return id;
        });
        return subscriptions.find(app.id(), compedId).orElseThrow();
    }

    private String newSubscription(AppPrincipal app, String customerId, PriceRecord price) {
        CustomerRecord customer = customers.find(app.id(), customerId)
                .orElseThrow(() -> ApiProblem.notFound("Customer", customerId));
        SubscriptionRecord s = new SubscriptionRecord(Ids.next("sub"), app.id(), customer.id(), price.id(),
                SubscriptionStatus.INCOMPLETE.name(), "PREPAID_MANUAL", null, null, null,
                properties.app(app.name()).billing().graceDays(), false, null, null, true, null, "{}", null, null, null, null, null);
        subscriptions.insert(s);
        transitions.created(app, s.id());
        return s.id();
    }
}
