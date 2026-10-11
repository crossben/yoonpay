package dev.yoonpay.server.api;

import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.billing.BillingViews;
import dev.yoonpay.server.billing.InvoiceRepository;
import dev.yoonpay.server.billing.SubscriptionRecord;
import dev.yoonpay.server.billing.SubscriptionRepository;
import dev.yoonpay.server.billing.SubscriptionService;
import dev.yoonpay.server.lifecycle.EventResponse;
import dev.yoonpay.server.lifecycle.StatusEvents;
import dev.yoonpay.server.web.ApiProblem;
import dev.yoonpay.server.web.IdempotentCall;
import dev.yoonpay.server.web.Page;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Locale;

/** Subscriptions (ADR-0026): prepaid periods, each paid through Yoon's hosted checkout. */
@RestController
@RequestMapping("/v1/subscriptions")
public class SubscriptionController {

    public record CreateSubscriptionRequest(@NotBlank String customer, @NotBlank String price,
                                            @Min(0) @Max(90) Integer gracePeriodDays, JsonNode metadata,
                                            @Size(max = 2048) String returnUrl) {
    }

    public record RenewRequest(@Size(max = 2048) String returnUrl) {
    }

    public record ChangeRequest(@NotBlank String price, String when, @Size(max = 2048) String returnUrl) {
    }

    public record CancelRequest(Boolean atPeriodEnd) {
    }

    private final SubscriptionService service;
    private final SubscriptionRepository subscriptions;
    private final InvoiceRepository invoices;
    private final BillingViews views;
    private final StatusEvents events;
    private final IdempotentCall idempotent;

    public SubscriptionController(SubscriptionService service, SubscriptionRepository subscriptions,
                                  InvoiceRepository invoices, BillingViews views, StatusEvents events,
                                  IdempotentCall idempotent) {
        this.service = service;
        this.subscriptions = subscriptions;
        this.invoices = invoices;
        this.views = views;
        this.events = events;
        this.idempotent = idempotent;
    }

    @PostMapping
    ResponseEntity<String> create(AppPrincipal app, @RequestHeader(value = IdempotentCall.HEADER, required = false) String key,
                                  @Valid @RequestBody CreateSubscriptionRequest body, HttpServletRequest request) {
        return idempotent.run(app, key, request, body, HttpStatus.CREATED, () -> views.subscription(service.create(app,
                new SubscriptionService.CreateRequest(body.customer(), body.price(), body.gracePeriodDays(),
                        body.metadata(), body.returnUrl()))));
    }

    @GetMapping("/{id}")
    BillingViews.SubscriptionView get(AppPrincipal app, @PathVariable String id) {
        return views.subscription(find(app, id));
    }

    @GetMapping
    Page<BillingViews.SubscriptionView> list(AppPrincipal app,
                                             @RequestParam(required = false) String customer,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(name = "starting_after", required = false) String startingAfter,
                                             @RequestParam(required = false) Integer limit) {
        int n = Page.limit(limit);
        return Page.of(subscriptions.list(app.id(), customer, status == null ? null : status.toUpperCase(Locale.ROOT),
                startingAfter, n), n, SubscriptionRecord::id).map(views::subscription);
    }

    @GetMapping("/{id}/events")
    List<EventResponse> history(AppPrincipal app, @PathVariable String id) {
        find(app, id);
        return events.history(app.id(), "subscription", id).stream().map(EventResponse::of).toList();
    }

    @PostMapping("/{id}/renew")
    ResponseEntity<String> renew(AppPrincipal app, @PathVariable String id,
                                 @RequestHeader(value = IdempotentCall.HEADER, required = false) String key,
                                 @Valid @RequestBody RenewRequest body, HttpServletRequest request) {
        return idempotent.run(app, key, request, body, HttpStatus.OK,
                () -> views.invoice(service.renew(app, id, body.returnUrl())));
    }

    @PostMapping("/{id}/change")
    ResponseEntity<String> change(AppPrincipal app, @PathVariable String id,
                                  @RequestHeader(value = IdempotentCall.HEADER, required = false) String key,
                                  @Valid @RequestBody ChangeRequest body, HttpServletRequest request) {
        return idempotent.run(app, key, request, body, HttpStatus.OK,
                () -> views.subscription(service.change(app, id, body.price(), body.when(), body.returnUrl())));
    }

    @PostMapping("/{id}/cancel")
    ResponseEntity<String> cancel(AppPrincipal app, @PathVariable String id,
                                  @RequestHeader(value = IdempotentCall.HEADER, required = false) String key,
                                  @RequestBody(required = false) CancelRequest body, HttpServletRequest request) {
        boolean atPeriodEnd = body == null || body.atPeriodEnd() == null || body.atPeriodEnd();
        return idempotent.run(app, key, request, body == null ? new CancelRequest(true) : body, HttpStatus.OK,
                () -> views.subscription(service.cancel(app, id, atPeriodEnd)));
    }

    @PostMapping("/{id}/resume")
    ResponseEntity<String> resume(AppPrincipal app, @PathVariable String id,
                                  @RequestHeader(value = IdempotentCall.HEADER, required = false) String key,
                                  HttpServletRequest request) {
        return idempotent.run(app, key, request, java.util.Map.of("resume", id), HttpStatus.OK,
                () -> views.subscription(service.resume(app, id)));
    }

    private SubscriptionRecord find(AppPrincipal app, String id) {
        return subscriptions.find(app.id(), id).orElseThrow(() -> ApiProblem.notFound("Subscription", id));
    }
}
