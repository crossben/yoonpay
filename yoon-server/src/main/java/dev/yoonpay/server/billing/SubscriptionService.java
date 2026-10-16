package dev.yoonpay.server.billing;

import dev.yoonpay.core.billing.BillingPeriods;
import dev.yoonpay.core.id.Ids;
import dev.yoonpay.core.lifecycle.InvoiceStatus;
import dev.yoonpay.core.lifecycle.SubscriptionStatus;
import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.config.YoonProperties;
import dev.yoonpay.server.lifecycle.StatusEvents.Cause;
import dev.yoonpay.server.payment.CreatePaymentRequest;
import dev.yoonpay.server.payment.PaymentRecord;
import dev.yoonpay.server.payment.PaymentRepository;
import dev.yoonpay.server.payment.PaymentService;
import dev.yoonpay.server.web.ApiProblem;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Subscriptions (ADR-0026): creating them, and the customer- or merchant-initiated changes. Every
 * period is an invoice paid through an ordinary hosted payment; nothing here activates anything —
 * only a confirmed payment does ({@link BillingPayments}).
 */
@Service
public class SubscriptionService {

    public record CreateRequest(String customer, String price, Integer gracePeriodDays, JsonNode metadata,
                                String returnUrl, Integer trialDays, String coupon) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SubscriptionRepository subscriptions;
    private final InvoiceRepository invoices;
    private final CustomerRepository customers;
    private final CatalogRepository catalog;
    private final SubscriptionTransitions transitions;
    private final InvoiceTransitions invoiceTransitions;
    private final PaymentService payments;
    private final PaymentRepository paymentRows;
    private final YoonProperties properties;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;
    private final Discounting discounting;
    private final BillingPayments billingPayments;

    public SubscriptionService(SubscriptionRepository subscriptions, InvoiceRepository invoices,
                               CustomerRepository customers, CatalogRepository catalog,
                               SubscriptionTransitions transitions, InvoiceTransitions invoiceTransitions,
                               PaymentService payments, PaymentRepository paymentRows, YoonProperties properties,
                               TransactionTemplate tx, ObjectMapper json, Clock clock, Discounting discounting,
                               BillingPayments billingPayments) {
        this.discounting = discounting;
        this.billingPayments = billingPayments;
        this.subscriptions = subscriptions;
        this.invoices = invoices;
        this.customers = customers;
        this.catalog = catalog;
        this.transitions = transitions;
        this.invoiceTransitions = invoiceTransitions;
        this.payments = payments;
        this.paymentRows = paymentRows;
        this.properties = properties;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
    }

    private YoonProperties.Billing settings(AppPrincipal app) {
        return properties.app(app.name()).billing();
    }

    // ------------------------------------------------------------------ create

    public SubscriptionRecord create(AppPrincipal app, CreateRequest req) {
        CustomerRecord customer = customers.find(app.id(), req.customer())
                .orElseThrow(() -> ApiProblem.unprocessable("customer_not_found", "No customer " + req.customer()));
        PriceRecord price = activePrice(app, req.price());
        int grace = req.gracePeriodDays() == null ? settings(app).graceDays() : req.gracePeriodDays();
        if (grace < 0 || grace > 90) {
            throw ApiProblem.invalid("grace_period_days must be 0–90");
        }
        int trialDays = trialDays(app, customer, price, req.trialDays());
        if (trialDays > 0) {
            // A trial (ADR-0028): entitled now, nothing to pay until it ends; no invoice yet.
            Instant now = clock.instant();
            Instant trialEnd = now.plus(java.time.Duration.ofDays(trialDays));
            SubscriptionRecord s = new SubscriptionRecord(Ids.next("sub"), app.id(), customer.id(), price.id(),
                    SubscriptionStatus.TRIALING.name(), "PREPAID_MANUAL", null, now, trialEnd, grace, false, null, null,
                    false, null, Metadata.metadata(req.metadata(), json), null, null, trialEnd, null, null);
            tx.executeWithoutResult(t -> {
                subscriptions.insert(s);
                redeemAtCreation(app, s.id(), req.coupon());
                transitions.created(app, s.id());
            });
            return subscriptions.find(app.id(), s.id()).orElseThrow();
        }
        requirePublicUrl();
        SubscriptionRecord s = new SubscriptionRecord(Ids.next("sub"), app.id(), customer.id(), price.id(),
                SubscriptionStatus.INCOMPLETE.name(), "PREPAID_MANUAL", null, null, null, grace, false, null, null,
                false, null, Metadata.metadata(req.metadata(), json), null, null, null, null, null);
        InvoiceRecord invoice = tx.execute(t -> {
            subscriptions.insert(s);
            redeemAtCreation(app, s.id(), req.coupon());
            transitions.created(app, s.id());
            return newInvoice(app, s, price, "FIRST");
        });
        ensurePayment(app, invoice, customer, req.returnUrl());
        return subscriptions.find(app.id(), s.id()).orElseThrow();
    }

    private void redeemAtCreation(AppPrincipal app, String subscriptionId, String code) {
        if (code == null) {
            return;
        }
        SubscriptionRecord locked = subscriptions.lock(app.id(), subscriptionId).orElseThrow();
        subscriptions.update(subscriptionId, discounting.redeem(app, locked, code));
    }

    /** The trial this subscription gets: the request's, else the plan's; none if the customer had one on this plan. */
    private int trialDays(AppPrincipal app, CustomerRecord customer, PriceRecord price, Integer requested) {
        if (requested != null && (requested < 0 || requested > 90)) {
            throw ApiProblem.invalid("trial_days must be 0–90");
        }
        int days = requested != null ? requested
                : catalog.findPlan(app.id(), price.planId()).orElseThrow().trialDays();
        if (days == 0 || !subscriptions.hadTrial(customer.id(), price.planId())) {
            return days;
        }
        if (requested != null) {
            throw new ApiProblem(org.springframework.http.HttpStatus.CONFLICT, "trial_already_used",
                    "This customer already had a trial on this plan; grant more time with a comp instead");
        }
        return 0; // the plan's default trial is for first-timers only
    }

    // ------------------------------------------------------------------ renew

    /** The open invoice (created if there is none) with a payable checkout. Safe to call repeatedly. */
    public InvoiceRecord renew(AppPrincipal app, String id, String returnUrl) {
        requirePublicUrl();
        SubscriptionRecord s = subscriptions.find(app.id(), id).orElseThrow(() -> ApiProblem.notFound("Subscription", id));
        boolean neverPaid = SubscriptionStatus.TRIALING.name().equals(s.status())
                || SubscriptionStatus.INCOMPLETE.name().equals(s.status());
        InvoiceRecord invoice = openInvoice(app, s, neverPaid ? "FIRST" : "RENEWAL");
        CustomerRecord customer = customers.find(app.id(), s.customerId()).orElseThrow();
        ensurePayment(app, invoice, customer, returnUrl);
        return invoices.find(app.id(), invoice.id()).orElseThrow();
    }

    /** The subscription's open invoice, or a new one at the price the next period will cost. */
    InvoiceRecord openInvoice(AppPrincipal app, SubscriptionRecord s, String kind) {
        try {
            return tx.execute(t -> {
                subscriptions.lock(app.id(), s.id()).orElseThrow();
                var open = invoices.lockOpen(s.id());
                if (open.isPresent()) {
                    return open.get();
                }
                String priceId = s.pendingPriceId() != null ? s.pendingPriceId() : s.priceId();
                return newInvoice(app, s, catalog.findPrice(app.id(), priceId).orElseThrow(), kind);
            });
        } catch (DuplicateKeyException e) {
            // A concurrent renew created it first: one open invoice per subscription.
            return invoices.latest(s.id()).orElseThrow();
        }
    }

    // ------------------------------------------------------------------ change

    public SubscriptionRecord change(AppPrincipal app, String id, String priceId, String when, String returnUrl) {
        return change(app, id, priceId, when, returnUrl, Cause.api);
    }

    public SubscriptionRecord change(AppPrincipal app, String id, String priceId, String when, String returnUrl, Cause cause) {
        SubscriptionRecord s = subscriptions.find(app.id(), id).orElseThrow(() -> ApiProblem.notFound("Subscription", id));
        SubscriptionStatus status = SubscriptionStatus.valueOf(s.status());
        if (status == SubscriptionStatus.TRIALING) {
            return changeDuringTrial(app, s, priceId, cause);
        }
        if (status != SubscriptionStatus.ACTIVE && status != SubscriptionStatus.PAST_DUE) {
            throw ApiProblem.unprocessable("subscription_not_active", "Only an active or past-due subscription can change plan");
        }
        PriceRecord current = catalog.findPrice(app.id(), s.priceId()).orElseThrow();
        PriceRecord next = activePrice(app, priceId);
        if (!next.currency().equals(current.currency())) {
            throw ApiProblem.unprocessable("currency_mismatch", "A plan change keeps the currency; start a new subscription instead");
        }
        if (next.id().equals(current.id())) {
            return s;
        }
        Instant now = clock.instant();
        boolean upgrade = BillingPeriods.isUpgrade(current.amount(), nominal(current, now), next.amount(), nominal(next, now));
        String effective = when == null ? (upgrade ? "now" : "period_end") : when;
        if (!effective.equals("now") && !effective.equals("period_end")) {
            throw ApiProblem.invalid("when must be now or period_end");
        }
        if (effective.equals("now") && !upgrade) {
            throw ApiProblem.unprocessable("change_not_upgrade",
                    "Only an upgrade can apply now; a downgrade or a cheaper period applies at the period end");
        }
        voidOpenInvoice(app, s.id(), "replaced by a plan change", cause);
        if (effective.equals("period_end")) {
            transitions.update(app, s.id(), cause, "change to " + next.id() + " at period end",
                    Map.of("pending_price_id", next.id()), "subscription.changed");
            return subscriptions.find(app.id(), id).orElseThrow();
        }
        requirePublicUrl();
        InvoiceRecord invoice = tx.execute(t -> newInvoice(app, s, next, "UPGRADE"));
        ensurePayment(app, invoice, customers.find(app.id(), s.customerId()).orElseThrow(), returnUrl);
        return subscriptions.find(app.id(), id).orElseThrow();
    }

    /** Nothing was paid during a trial: a change only switches the price the first invoice will charge. */
    private SubscriptionRecord changeDuringTrial(AppPrincipal app, SubscriptionRecord s, String priceId, Cause cause) {
        PriceRecord current = catalog.findPrice(app.id(), s.priceId()).orElseThrow();
        PriceRecord next = activePrice(app, priceId);
        if (!next.currency().equals(current.currency())) {
            throw ApiProblem.unprocessable("currency_mismatch", "A plan change keeps the currency; start a new subscription instead");
        }
        if (next.id().equals(current.id())) {
            return s;
        }
        voidOpenInvoice(app, s.id(), "replaced by a plan change during the trial", cause);
        Map<String, Object> fields = new HashMap<>();
        fields.put("price_id", next.id());
        fields.put("pending_price_id", null);
        transitions.update(app, s.id(), cause, "change to " + next.id() + " during the trial", fields, "subscription.changed");
        return subscriptions.find(app.id(), s.id()).orElseThrow();
    }

    // ------------------------------------------------------------------ discount

    /**
     * Applies a coupon to the subscription's next invoices ({@code code}), or removes its discount
     * ({@code null}). An open invoice without a payment in progress is voided so the next one is
     * priced with the new discount; with a payment in progress it is refused (409).
     */
    public SubscriptionRecord setDiscount(AppPrincipal app, String id, String code) {
        return setDiscount(app, id, code, Cause.api);
    }

    public SubscriptionRecord setDiscount(AppPrincipal app, String id, String code, Cause cause) {
        tx.executeWithoutResult(t -> {
            SubscriptionRecord s = subscriptions.lock(app.id(), id).orElseThrow(() -> ApiProblem.notFound("Subscription", id));
            SubscriptionStatus status = SubscriptionStatus.valueOf(s.status());
            if (status == SubscriptionStatus.EXPIRED || status == SubscriptionStatus.CANCELED) {
                throw ApiProblem.unprocessable("subscription_ended", "This subscription has ended");
            }
            if (code == null && s.couponId() == null) {
                return;
            }
            invoices.lockOpen(id).ifPresent(open -> {
                if (open.paymentId() != null && paymentRows.find(app.id(), open.paymentId())
                        .map(p -> "PENDING".equals(p.status())).orElse(false)) {
                    throw new ApiProblem(org.springframework.http.HttpStatus.CONFLICT, "payment_in_progress",
                            "The open invoice is being paid; change the discount once it settles");
                }
                invoiceTransitions.apply(app, open.id(), InvoiceStatus.VOID, cause, "replaced: discount changed",
                        Map.of("voided_at", clock.instant()));
            });
            Map<String, Object> fields = new HashMap<>();
            if (code == null) {
                fields.put("coupon_id", null);
                fields.put("discount_periods_remaining", null);
            } else {
                fields.putAll(discounting.redeem(app, s, code));
            }
            transitions.update(app, id, cause, code == null ? "discount removed" : "coupon " + code + " applied",
                    fields, "subscription.changed");
        });
        return subscriptions.find(app.id(), id).orElseThrow();
    }

    // ------------------------------------------------------------------ cancel / resume

    public SubscriptionRecord cancel(AppPrincipal app, String id, boolean atPeriodEnd) {
        return cancel(app, id, atPeriodEnd, Cause.api);
    }

    public SubscriptionRecord cancel(AppPrincipal app, String id, boolean atPeriodEnd, Cause cause) {
        SubscriptionRecord s = subscriptions.find(app.id(), id).orElseThrow(() -> ApiProblem.notFound("Subscription", id));
        SubscriptionStatus status = SubscriptionStatus.valueOf(s.status());
        if (atPeriodEnd && (status == SubscriptionStatus.ACTIVE || status == SubscriptionStatus.TRIALING)
                && s.currentPeriodEnd() != null) {
            transitions.update(app, id, cause, "cancel at period end", Map.of("cancel_at_period_end", true),
                    "subscription.changed");
        } else if (status == SubscriptionStatus.EXPIRED || status == SubscriptionStatus.CANCELED) {
            return s;
        } else {
            voidOpenInvoice(app, id, "subscription canceled", cause);
            Map<String, Object> fields = new HashMap<>();
            fields.put("canceled_at", clock.instant());
            fields.put("cancel_at_period_end", false);
            fields.put("pending_price_id", null);
            transitions.apply(app, id, SubscriptionStatus.CANCELED, cause, "canceled now", fields);
        }
        return subscriptions.find(app.id(), id).orElseThrow();
    }

    public SubscriptionRecord resume(AppPrincipal app, String id) {
        return resume(app, id, Cause.api);
    }

    public SubscriptionRecord resume(AppPrincipal app, String id, Cause cause) {
        SubscriptionRecord s = subscriptions.find(app.id(), id).orElseThrow(() -> ApiProblem.notFound("Subscription", id));
        if (!s.cancelAtPeriodEnd()) {
            return s;
        }
        transitions.update(app, id, cause, "cancellation withdrawn", Map.of("cancel_at_period_end", false),
                "subscription.changed");
        return subscriptions.find(app.id(), id).orElseThrow();
    }

    // ------------------------------------------------------------------ helpers

    private InvoiceRecord newInvoice(AppPrincipal app, SubscriptionRecord unlocked, PriceRecord price, String kind) {
        // Billing lock order: the subscription first (its discount is read and later consumed under it).
        SubscriptionRecord s = subscriptions.lock(app.id(), unlocked.id()).orElseThrow();
        PlanRecord plan = catalog.findPlan(app.id(), price.planId()).orElseThrow();
        int year = clock.instant().atZone(settings(app).timezone()).getYear();
        var discount = discounting.forInvoice(s, price);
        long off = discount.map(Discounting.Applied::amountOff).orElse(0L);
        InvoiceRecord i = new InvoiceRecord(Ids.next("in"), app.id(),
                invoices.nextNumber(app.id(), settings(app).invoicePrefix(), year), s.customerId(), s.id(),
                price.id(), kind, InvoiceStatus.OPEN.name(), price.amount() - off, price.currency(), null, null, null,
                token(), null, null, null, null, price.amount(), off, discount.map(Discounting.Applied::couponId).orElse(null));
        invoices.insert(i, List.of(new InvoiceRepository.Line(describe(plan, price), price.id(), price.amount())));
        InvoiceRecord stored = invoices.find(app.id(), i.id()).orElseThrow();
        invoiceTransitions.created(app, stored);
        if (stored.amountDue() == 0) {
            // Nothing to pay (100% off): paid now, without a payment (ADR-0029).
            billingPayments.paidWithoutPayment(app, s.id(), stored.id(), Cause.api);
            return invoices.find(app.id(), stored.id()).orElseThrow();
        }
        return stored;
    }

    /** Gives an open invoice a hosted payment the customer can still use (a new one if the last expired). */
    private void ensurePayment(AppPrincipal app, InvoiceRecord unlocked, CustomerRecord customer, String returnUrl) {
        // Locks in the billing order — subscription, then invoice (then payment) — so two concurrent
        // renewals give the invoice one payment, and nothing deadlocks with a payment being settled.
        tx.executeWithoutResult(t -> {
            subscriptions.lock(app.id(), unlocked.subscriptionId()).orElseThrow();
            InvoiceRecord invoice = invoices.lock(app.id(), unlocked.id()).orElseThrow();
            ensurePaymentLocked(app, invoice, customer, returnUrl);
        });
    }

    private void ensurePaymentLocked(AppPrincipal app, InvoiceRecord invoice, CustomerRecord customer, String returnUrl) {
        if (!InvoiceStatus.OPEN.name().equals(invoice.status())) {
            return;
        }
        if (invoice.paymentId() != null) {
            PaymentRecord p = paymentRows.find(app.id(), invoice.paymentId()).orElse(null);
            if (p != null && ("CREATED".equals(p.status()) || "PENDING".equals(p.status()))) {
                return;
            }
        }
        PlanRecord plan = catalog.findPlan(app.id(), catalog.findPrice(app.id(), invoice.priceId()).orElseThrow().planId()).orElseThrow();
        PaymentRecord payment = payments.create(app, new CreatePaymentRequest(invoice.amountDue(), invoice.currency(),
                customer.country(), null,
                customer.phone() == null && customer.piAlias() == null ? null
                        : new CreatePaymentRequest.Customer(customer.phone(), customer.piAlias()),
                invoice.number(), plan.name() + " — " + invoice.number(), returnUrl, null, "hosted"));
        invoices.linkPayment(invoice.id(), payment.id(), invoice.subscriptionId(), invoice.customerId());
    }

    private void voidOpenInvoice(AppPrincipal app, String subscriptionId, String why, Cause cause) {
        tx.executeWithoutResult(t -> {
            subscriptions.lock(app.id(), subscriptionId).orElseThrow();   // billing lock order: subscription first
            invoices.lockOpen(subscriptionId).ifPresent(i -> invoiceTransitions.apply(app, i.id(), InvoiceStatus.VOID,
                    cause, why, Map.of("voided_at", clock.instant())));
        });
    }

    private PriceRecord activePrice(AppPrincipal app, String priceId) {
        PriceRecord price = catalog.findPrice(app.id(), priceId)
                .orElseThrow(() -> ApiProblem.unprocessable("price_not_found", "No price " + priceId));
        PlanRecord plan = catalog.findPlan(app.id(), price.planId()).orElseThrow();
        if (!price.active() || !plan.active()) {
            throw ApiProblem.unprocessable("price_archived", "This price or its plan is archived");
        }
        return price;
    }

    private void requirePublicUrl() {
        if (properties.publicUrl() == null) {
            throw ApiProblem.unprocessable("public_url_required",
                    "Subscriptions are paid on Yoon's checkout page: set YOON_PUBLIC_URL");
        }
    }

    static Duration nominal(PriceRecord p, Instant from) {
        return Duration.between(from, BillingPeriods.periodEnd(from, p.intervalEnum(), p.intervalCount(),
                BillingPeriods.anchorOf(from)));
    }

    private static String describe(PlanRecord plan, PriceRecord p) {
        String unit = p.interval().toLowerCase(java.util.Locale.ROOT);
        return plan.name() + ", " + (p.intervalCount() == 1 ? "1 " + unit : p.intervalCount() + " " + unit + "s");
    }

    private static String token() {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
