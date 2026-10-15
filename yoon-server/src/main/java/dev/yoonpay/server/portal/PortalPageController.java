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

    public PortalPageController(PortalService portal, Applications applications, CustomerRepository customers,
                                SubscriptionRepository subscriptions, InvoiceRepository invoices,
                                CatalogRepository catalog, BillingViews views) {
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

    PortalSessionRecord session(String id, String token) {
        return portal.authorize(id, token).orElseThrow(() ->
                new ApiProblem(HttpStatus.NOT_FOUND, "resource_not_found", "Portal session not found"));
    }
}
