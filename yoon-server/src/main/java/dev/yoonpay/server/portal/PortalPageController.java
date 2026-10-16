package dev.yoonpay.server.portal;

import dev.yoonpay.server.auth.Applications;
import dev.yoonpay.server.billing.BillingViews;
import dev.yoonpay.server.billing.CatalogRepository;
import dev.yoonpay.server.billing.CustomerRepository;
import dev.yoonpay.server.billing.InvoiceRepository;
import dev.yoonpay.server.billing.PlanRecord;
import dev.yoonpay.server.billing.PriceRecord;
import dev.yoonpay.server.billing.SubscriptionRepository;
import dev.yoonpay.server.checkout.CheckoutController;
import dev.yoonpay.server.web.ApiProblem;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * The customer portal (ADR-0031), public and token-scoped like the checkout and under its rules.
 * It shows one customer's subscriptions and invoices — nothing of the merchant's own data
 * (metadata, external ids) and nothing of another customer. Unknown, wrong-token and expired
 * sessions are the same 404.
 */
@Controller
public class PortalPageController {

    public record PriceOption(String id, String plan, long amount, String currency, String interval, int intervalCount) {
    }

    public record Allow(boolean cancel, boolean coupons, List<PriceOption> prices) {
    }

    public record OpenInvoice(String number, long amountDue, String currency, String checkoutUrl) {
    }

    public record SubscriptionItem(String id, String plan, long amount, String currency, String interval,
                                   int intervalCount, String status, boolean entitled, Instant paidUntil,
                                   Instant trialEnd, boolean cancelAtPeriodEnd, String pendingPlan,
                                   String discountCode, Integer discountPeriodsRemaining, OpenInvoice openInvoice) {
    }

    public record InvoiceItem(String number, String status, long amountDue, String currency, Instant paidAt,
                              Instant periodStart, Instant periodEnd, String receiptUrl) {
    }

    /** One customer action; {@code attempt} is random per click, so a double click acts once. */
    public record ActionRequest(
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Pattern(regexp = "renew|cancel|resume|change|coupon") String action,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 64) String subscription,
            @jakarta.validation.constraints.Size(max = 64) String price,
            @jakarta.validation.constraints.Size(max = 32) String coupon,
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9-]{8,64}") String attempt) {
    }

    /** Where to send the customer next: a checkout to pay, or null (reload the portal). */
    public record ActionResult(String checkoutUrl) {
    }

    public record PortalView(String merchant, String customer, String returnUrl, Instant expiresAt, Allow allow,
                             List<SubscriptionItem> subscriptions, List<InvoiceItem> invoices) {
    }

    private final PortalService portal;
    private final Applications applications;
    private final CustomerRepository customers;
    private final SubscriptionRepository subscriptions;
    private final InvoiceRepository invoices;
    private final CatalogRepository catalog;
    private final BillingViews views;
    private final dev.yoonpay.server.billing.SubscriptionService service;
    private final dev.yoonpay.server.web.IdempotentCall idempotent;

    public PortalPageController(PortalService portal, Applications applications, CustomerRepository customers,
                                SubscriptionRepository subscriptions, InvoiceRepository invoices,
                                CatalogRepository catalog, BillingViews views,
                                dev.yoonpay.server.billing.SubscriptionService service,
                                dev.yoonpay.server.web.IdempotentCall idempotent) {
        this.service = service;
        this.idempotent = idempotent;
        this.portal = portal;
        this.applications = applications;
        this.customers = customers;
        this.subscriptions = subscriptions;
        this.invoices = invoices;
        this.catalog = catalog;
        this.views = views;
    }

    @GetMapping("/checkout/portal/{id}")
    String page(@PathVariable String id, @RequestParam(name = "t", required = false) String token) {
        session(id, token);
        return "forward:/checkout/portal.html";
    }

    @GetMapping("/checkout/api/portal/{id}")
    @ResponseBody
    PortalView view(@PathVariable String id,
                    @RequestHeader(name = CheckoutController.TOKEN_HEADER, required = false) String token) {
        PortalSessionRecord s = session(id, token);
        var app = s.applicationId();
        String merchant = applications.find(app).map(a -> a.name()).orElse(null);
        String customer = customers.find(app, s.customerId()).map(c -> c.name()).orElse(null);

        List<SubscriptionItem> subs = subscriptions.list(app, s.customerId(), null, null, 50).stream()
                .map(views::subscription)
                .map(v -> {
                    var inv = v.latestInvoice();
                    OpenInvoice open = inv != null && "open".equals(inv.status())
                            ? new OpenInvoice(inv.number(), inv.amountDue(), inv.currency(), inv.checkoutUrl()) : null;
                    return new SubscriptionItem(v.id(), v.price().plan().name(), v.price().amount(), v.price().currency(),
                            v.price().interval(), v.price().intervalCount(), v.status(), v.entitled(),
                            v.currentPeriodEnd(), v.trialEnd(), v.cancelAtPeriodEnd(),
                            v.pendingChange() == null ? null : v.pendingChange().price().plan().name(),
                            v.discount() == null ? null : v.discount().code(),
                            v.discount() == null ? null : v.discount().periodsRemaining(), open);
                })
                .toList();

        List<InvoiceItem> invs = invoices.list(app, s.customerId(), null, null, null, 24).stream()
                .limit(24)
                .map(i -> new InvoiceItem(i.number(), i.status().toLowerCase(Locale.ROOT), i.amountDue(), i.currency(),
                        i.paidAt(), i.periodStart(), i.periodEnd(),
                        "PAID".equals(i.status()) ? BillingViews.receiptPath(i) : null))
                .toList();

        List<PriceOption> prices = PortalService.changePrices(s).stream()
                .map(p -> catalog.findPrice(app, p).orElse(null))
                .filter(p -> p != null && p.active())
                .map(p -> option(app, p))
                .toList();
        return new PortalView(merchant, customer, s.returnUrl(), s.expiresAt(),
                new Allow(s.allowCancel(), s.allowCoupons(), prices), subs, invs);
    }

    private PriceOption option(java.util.UUID app, PriceRecord p) {
        PlanRecord plan = catalog.findPlan(app, p.planId()).orElseThrow();
        return new PriceOption(p.id(), plan.name(), p.amount(), p.currency(), p.interval().toLowerCase(Locale.ROOT),
                p.intervalCount());
    }

    @org.springframework.web.bind.annotation.PostMapping("/checkout/api/portal/{id}/actions")
    org.springframework.http.ResponseEntity<String> act(@PathVariable String id,
            @RequestHeader(name = CheckoutController.TOKEN_HEADER, required = false) String token,
            @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody ActionRequest body,
            jakarta.servlet.http.HttpServletRequest request) {
        PortalSessionRecord s = session(id, token);
        var app = applications.find(s.applicationId()).orElseThrow();
        // The customer's own subscription only; any other id is the same 404 as an unknown one.
        var sub = subscriptions.find(app.id(), body.subscription())
                .filter(x -> x.customerId().equals(s.customerId()))
                .orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND, "resource_not_found", "Subscription not found"));
        return idempotent.run(app, "portal:" + s.id() + ":" + body.attempt(), request, body, HttpStatus.OK, () -> {
            var cause = dev.yoonpay.server.lifecycle.StatusEvents.Cause.portal;
            return switch (body.action()) {
                case "renew" -> new ActionResult(views.invoice(service.renew(app, sub.id(), s.returnUrl())).checkoutUrl());
                case "cancel" -> {
                    allowed(s.allowCancel());
                    service.cancel(app, sub.id(), true, cause);
                    yield new ActionResult(null);
                }
                case "resume" -> {
                    allowed(s.allowCancel());
                    service.resume(app, sub.id(), cause);
                    yield new ActionResult(null);
                }
                case "change" -> {
                    if (body.price() == null || !PortalService.changePrices(s).contains(body.price())) {
                        throw new ApiProblem(HttpStatus.FORBIDDEN, "action_not_allowed", "This plan is not offered here");
                    }
                    var changed = views.subscription(service.change(app, sub.id(), body.price(), null, s.returnUrl(), cause));
                    var inv = changed.latestInvoice();
                    // An upgrade is paid now: send the customer to its checkout.
                    yield new ActionResult(inv != null && "open".equals(inv.status()) && "upgrade".equals(inv.kind())
                            ? inv.checkoutUrl() : null);
                }
                case "coupon" -> {
                    allowed(s.allowCoupons());
                    if (body.coupon() == null || body.coupon().isBlank()) {
                        throw ApiProblem.invalid("enter a coupon code");
                    }
                    service.setDiscount(app, sub.id(), body.coupon().strip(), cause);
                    yield new ActionResult(null);
                }
                default -> throw ApiProblem.invalid("unknown action");
            };
        });
    }

    private static void allowed(boolean allowed) {
        if (!allowed) {
            throw new ApiProblem(HttpStatus.FORBIDDEN, "action_not_allowed", "This action is not available here");
        }
    }

    PortalSessionRecord session(String id, String token) {
        return portal.authorize(id, token).orElseThrow(() ->
                new ApiProblem(HttpStatus.NOT_FOUND, "resource_not_found", "Portal session not found"));
    }
}
