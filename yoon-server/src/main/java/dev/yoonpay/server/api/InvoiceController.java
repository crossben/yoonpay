package dev.yoonpay.server.api;

import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.billing.BillingViews;
import dev.yoonpay.server.billing.InvoiceRecord;
import dev.yoonpay.server.billing.InvoiceRepository;
import dev.yoonpay.server.web.ApiProblem;
import dev.yoonpay.server.web.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/** Invoices (ADR-0027): billing records, read-only through the API. */
@RestController
@RequestMapping("/v1/invoices")
public class InvoiceController {

    private final InvoiceRepository invoices;
    private final BillingViews views;

    public InvoiceController(InvoiceRepository invoices, BillingViews views) {
        this.invoices = invoices;
        this.views = views;
    }

    @GetMapping("/{id}")
    BillingViews.InvoiceView get(AppPrincipal app, @PathVariable String id) {
        return views.invoice(invoices.find(app.id(), id).orElseThrow(() -> ApiProblem.notFound("Invoice", id)));
    }

    @GetMapping
    Page<BillingViews.InvoiceView> list(AppPrincipal app,
                                        @RequestParam(required = false) String customer,
                                        @RequestParam(required = false) String subscription,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(name = "starting_after", required = false) String startingAfter,
                                        @RequestParam(required = false) Integer limit) {
        int n = Page.limit(limit);
        return Page.of(invoices.list(app.id(), customer, subscription,
                status == null ? null : status.toUpperCase(Locale.ROOT), startingAfter, n), n, InvoiceRecord::id)
                .map(views::invoice);
    }
}
