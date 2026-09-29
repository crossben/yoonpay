package dev.yoonpay.server.api;

import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.lifecycle.EventResponse;
import dev.yoonpay.server.lifecycle.StatusEvents;
import dev.yoonpay.server.payout.CreatePayoutRequest;
import dev.yoonpay.server.payout.PayoutRepository;
import dev.yoonpay.server.payout.PayoutResponse;
import dev.yoonpay.server.payout.PayoutService;
import dev.yoonpay.server.web.ApiProblem;
import dev.yoonpay.server.web.IdempotentCall;
import dev.yoonpay.server.web.Page;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
import java.util.Locale;

@RestController
@RequestMapping("/v1/payouts")
public class PayoutController {

    private final PayoutService service;
    private final PayoutRepository payouts;
    private final StatusEvents events;
    private final IdempotentCall idempotent;

    public PayoutController(PayoutService service, PayoutRepository payouts, StatusEvents events, IdempotentCall idempotent) {
        this.service = service;
        this.payouts = payouts;
        this.events = events;
        this.idempotent = idempotent;
    }

    @PostMapping
    ResponseEntity<String> create(AppPrincipal app,
                                  @RequestHeader(value = IdempotentCall.HEADER, required = false) String key,
                                  @Valid @RequestBody CreatePayoutRequest body, HttpServletRequest request) {
        return idempotent.run(app, key, request, body, HttpStatus.CREATED,
                () -> PayoutResponse.of(service.create(app, body)));
    }

    @GetMapping("/{id}")
    PayoutResponse get(AppPrincipal app, @PathVariable String id) {
        return PayoutResponse.of(payouts.find(app.id(), id).orElseThrow(() -> ApiProblem.notFound("Payout", id)));
    }

    @GetMapping
    Page<PayoutResponse> list(AppPrincipal app,
                              @RequestParam(required = false) String status,
                              @RequestParam(required = false) String reference,
                              @RequestParam(name = "needs_review", required = false) Boolean needsReview,
                              @RequestParam(name = "starting_after", required = false) String startingAfter,
                              @RequestParam(required = false) Integer limit) {
        int n = Page.limit(limit);
        return Page.of(payouts.list(app.id(), status == null ? null : status.toUpperCase(Locale.ROOT), reference,
                needsReview, startingAfter, n), n, p -> p.id()).map(PayoutResponse::of);
    }

    @GetMapping("/{id}/events")
    List<EventResponse> events(AppPrincipal app, @PathVariable String id) {
        payouts.find(app.id(), id).orElseThrow(() -> ApiProblem.notFound("Payout", id));
        return events.history(app.id(), "payout", id).stream().map(EventResponse::of).toList();
    }
}
