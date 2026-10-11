package dev.yoonpay.server.api;

import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.auth.Applications;
import dev.yoonpay.server.billing.BillingViews;
import dev.yoonpay.server.billing.CompService;
import dev.yoonpay.server.billing.PlanResponse;
import dev.yoonpay.server.lifecycle.EventResponse;
import dev.yoonpay.server.web.ApiProblem;
import dev.yoonpay.server.web.IdempotentCall;
import dev.yoonpay.server.web.Page;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Operator views of one application's billing (behind {@code AdminFilter}), delegating to the
 * {@code /v1} queries like {@link AdminReadController}, and the one billing write an operator has:
 * granting periods without payment (comp, ADR-0026).
 */
@RestController
@RequestMapping("/admin/v1/applications/{applicationId}")
public class AdminBillingController {

    /** Exactly one of {@code periods} and {@code open: true}. */
    public record CompRequest(@NotBlank String price, Integer periods, Boolean open,
                              @NotBlank @Size(max = 500) String note) {
    }

    private final Applications applications;
    private final PlanController plans;
    private final SubscriptionController subscriptions;
    private final InvoiceController invoices;
    private final CompService comp;
    private final BillingViews views;
    private final IdempotentCall idempotent;

    public AdminBillingController(Applications applications, PlanController plans, SubscriptionController subscriptions,
                                  InvoiceController invoices, CompService comp, BillingViews views,
                                  IdempotentCall idempotent) {
        this.applications = applications;
        this.plans = plans;
        this.subscriptions = subscriptions;
        this.invoices = invoices;
        this.comp = comp;
        this.views = views;
        this.idempotent = idempotent;
    }

    @GetMapping("/plans")
    Page<PlanResponse> plans(@PathVariable String applicationId,
                             @RequestParam(required = false) Boolean active,
                             @RequestParam(name = "starting_after", required = false) String startingAfter,
                             @RequestParam(required = false) Integer limit) {
        return plans.list(app(applicationId), active, startingAfter, limit);
    }

    @GetMapping("/subscriptions")
    Page<BillingViews.SubscriptionView> subscriptions(@PathVariable String applicationId,
                                                      @RequestParam(required = false) String customer,
                                                      @RequestParam(required = false) String status,
                                                      @RequestParam(name = "starting_after", required = false) String startingAfter,
                                                      @RequestParam(required = false) Integer limit) {
        return subscriptions.list(app(applicationId), customer, status, startingAfter, limit);
    }

    @GetMapping("/subscriptions/{id}/events")
    List<EventResponse> subscriptionEvents(@PathVariable String applicationId, @PathVariable String id) {
        return subscriptions.history(app(applicationId), id);
    }

    @GetMapping("/invoices")
    Page<BillingViews.InvoiceView> invoices(@PathVariable String applicationId,
                                            @RequestParam(required = false) String customer,
                                            @RequestParam(required = false) String subscription,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(name = "starting_after", required = false) String startingAfter,
                                            @RequestParam(required = false) Integer limit) {
        return invoices.list(app(applicationId), customer, subscription, status, startingAfter, limit);
    }

    @PostMapping("/subscriptions/{id}/comp")
    ResponseEntity<String> compSubscription(@PathVariable String applicationId, @PathVariable String id,
                                            @RequestHeader(value = IdempotentCall.HEADER, required = false) String key,
                                            @Valid @RequestBody CompRequest body, HttpServletRequest request) {
        return comp(app(applicationId), id.startsWith("sub_") ? id : notFound("Subscription", id), key, body, request);
    }

    @PostMapping("/customers/{id}/comp")
    ResponseEntity<String> compCustomer(@PathVariable String applicationId, @PathVariable String id,
                                        @RequestHeader(value = IdempotentCall.HEADER, required = false) String key,
                                        @Valid @RequestBody CompRequest body, HttpServletRequest request) {
        return comp(app(applicationId), id.startsWith("cus_") ? id : notFound("Customer", id), key, body, request);
    }

    private ResponseEntity<String> comp(AppPrincipal app, String target, String key, CompRequest body,
                                        HttpServletRequest request) {
        boolean open = Boolean.TRUE.equals(body.open());
        if (open == (body.periods() != null)) {
            throw ApiProblem.invalid("Give either periods or open: true");
        }
        return idempotent.run(app, key, request, body, HttpStatus.OK,
                () -> views.subscription(comp.comp(app, target, body.price(), body.periods(), body.note())));
    }

    private static String notFound(String what, String id) {
        throw ApiProblem.notFound(what, id);
    }

    private AppPrincipal app(String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw ApiProblem.notFound("Application", id);
        }
        return applications.find(uuid).orElseThrow(() -> ApiProblem.notFound("Application", id));
    }
}
