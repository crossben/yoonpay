package dev.yoonpay.server.checkout;

import dev.yoonpay.server.auth.Applications;
import dev.yoonpay.server.billing.CatalogRepository;
import dev.yoonpay.server.billing.CustomerRepository;
import dev.yoonpay.server.billing.InvoiceRecord;
import dev.yoonpay.server.billing.InvoiceRepository;
import dev.yoonpay.server.billing.PlanRecord;
import dev.yoonpay.server.billing.PriceRecord;
import dev.yoonpay.server.payment.PaymentRepository;
import dev.yoonpay.server.web.ApiProblem;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * The public payment receipt of a paid invoice (ADR-0027), on the hosted-checkout pages and under
 * their rules (CSP, rate limit, no cache). The receipt token in the URL grants this one receipt;
 * unknown id, wrong token and an unpaid invoice are the same 404.
 */
@Controller
public class ReceiptController {

    private final InvoiceRepository invoices;
    private final CatalogRepository catalog;
    private final CustomerRepository customers;
    private final PaymentRepository payments;
    private final Applications applications;

    public ReceiptController(InvoiceRepository invoices, CatalogRepository catalog, CustomerRepository customers,
                             PaymentRepository payments, Applications applications) {
        this.invoices = invoices;
        this.catalog = catalog;
        this.customers = customers;
        this.payments = payments;
        this.applications = applications;
    }

    @GetMapping("/checkout/receipts/{id:in_[0-9a-f]+}")
    String page(@PathVariable String id, @RequestParam(name = "t", required = false) String token) {
        authorize(id, token);
        return "forward:/checkout/receipt.html";
    }

    @GetMapping("/checkout/api/receipts/{id}")
    @ResponseBody
    ReceiptView view(@PathVariable String id,
                     @RequestHeader(name = CheckoutController.TOKEN_HEADER, required = false) String token) {
        InvoiceRecord i = authorize(id, token);
        var app = i.applicationId();
        PriceRecord price = catalog.findPrice(app, i.priceId()).orElseThrow();
        PlanRecord plan = catalog.findPlan(app, price.planId()).orElseThrow();
        String merchant = applications.find(app).map(a -> a.name()).orElse(null);
        String customer = customers.find(app, i.customerId()).map(c -> c.name()).orElse(null);
        String method = i.paymentId() == null ? null
                : payments.find(app, i.paymentId()).map(p -> p.method()).orElse(null);
        var lines = invoices.lines(i.id()).stream().map(l -> new ReceiptView.Line(l.description(), l.amount())).toList();
        return new ReceiptView(i.number(), merchant, customer, plan.name(), price.interval().toLowerCase(Locale.ROOT),
                price.intervalCount(), lines, i.amountDue(), i.currency(), i.periodStart(), i.periodEnd(), i.paidAt(),
                method, i.paymentId());
    }

    private InvoiceRecord authorize(String id, String token) {
        if (token == null || token.isEmpty() || token.length() > 128 || id.length() > 64) {
            throw notFound();
        }
        InvoiceRecord i = invoices.findForReceipt(id).orElseThrow(ReceiptController::notFound);
        boolean match = MessageDigest.isEqual(i.receiptToken().getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
        if (!match || !"PAID".equals(i.status())) {
            throw notFound();
        }
        return i;
    }

    private static ApiProblem notFound() {
        return new ApiProblem(HttpStatus.NOT_FOUND, "resource_not_found", "Receipt not found");
    }
}
