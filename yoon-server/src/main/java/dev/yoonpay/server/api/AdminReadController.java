package dev.yoonpay.server.api;

import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.auth.Applications;
import dev.yoonpay.server.lifecycle.EventResponse;
import dev.yoonpay.server.payment.PaymentResponse;
import dev.yoonpay.server.payout.PayoutResponse;
import dev.yoonpay.server.refund.RefundResponse;
import dev.yoonpay.server.web.ApiProblem;
import dev.yoonpay.server.web.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-only operator views of one application's data (behind {@code AdminFilter}). Each route
 * resolves the application from the path and then runs exactly the query the application itself
 * would run through {@code /v1}, so scoping and response shapes are the same. Nothing here moves
 * money or changes state.
 */
@RestController
@RequestMapping("/admin/v1/applications")
public class AdminReadController {

    public record Application(UUID id, String object, String name, Instant createdAt) {
    }

    public record ApplicationList(List<Application> data) {
    }

    private final Applications applications;
    private final JdbcClient jdbc;
    private final PaymentController payments;
    private final RefundController refunds;
    private final PayoutController payouts;
    private final LedgerController ledger;

    public AdminReadController(Applications applications, JdbcClient jdbc, PaymentController payments,
                               RefundController refunds, PayoutController payouts, LedgerController ledger) {
        this.applications = applications;
        this.jdbc = jdbc;
        this.payments = payments;
        this.refunds = refunds;
        this.payouts = payouts;
        this.ledger = ledger;
    }

    @GetMapping
    ApplicationList list() {
        return new ApplicationList(jdbc.sql("SELECT id, 'application' AS object, name, created_at FROM applications ORDER BY name")
                .query(Application.class).list());
    }

    @GetMapping("/{applicationId}/payments")
    Page<PaymentResponse> payments(@PathVariable String applicationId,
                                   @RequestParam(required = false) String status,
                                   @RequestParam(required = false) String reference,
                                   @RequestParam(name = "starting_after", required = false) String startingAfter,
                                   @RequestParam(required = false) Integer limit) {
        return payments.list(app(applicationId), reference, status, null, null, null, null, null, startingAfter, limit);
    }

    @GetMapping("/{applicationId}/payments/{id}/events")
    List<EventResponse> paymentEvents(@PathVariable String applicationId, @PathVariable String id) {
        return payments.events(app(applicationId), id);
    }

    @GetMapping("/{applicationId}/refunds")
    Page<RefundResponse> refunds(@PathVariable String applicationId,
                                 @RequestParam(required = false) String status,
                                 @RequestParam(name = "starting_after", required = false) String startingAfter,
                                 @RequestParam(required = false) Integer limit) {
        return refunds.list(app(applicationId), status, startingAfter, limit);
    }

    @GetMapping("/{applicationId}/refunds/{id}/events")
    List<EventResponse> refundEvents(@PathVariable String applicationId, @PathVariable String id) {
        return refunds.events(app(applicationId), id);
    }

    @GetMapping("/{applicationId}/payouts")
    Page<PayoutResponse> payouts(@PathVariable String applicationId,
                                 @RequestParam(required = false) String status,
                                 @RequestParam(required = false) String reference,
                                 @RequestParam(name = "needs_review", required = false) Boolean needsReview,
                                 @RequestParam(name = "starting_after", required = false) String startingAfter,
                                 @RequestParam(required = false) Integer limit) {
        return payouts.list(app(applicationId), status, reference, needsReview, startingAfter, limit);
    }

    @GetMapping("/{applicationId}/payouts/{id}/events")
    List<EventResponse> payoutEvents(@PathVariable String applicationId, @PathVariable String id) {
        return payouts.events(app(applicationId), id);
    }

    @GetMapping("/{applicationId}/balances")
    LedgerController.Balances balances(@PathVariable String applicationId) {
        return ledger.balances(app(applicationId));
    }

    @GetMapping("/{applicationId}/ledger/entries")
    Page<LedgerController.Entry> entries(@PathVariable String applicationId,
                                         @RequestParam(required = false) String provider,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                         @RequestParam(name = "starting_after", required = false) Long startingAfter,
                                         @RequestParam(required = false) Integer limit) {
        return ledger.entries(app(applicationId), provider, from, to, startingAfter, limit);
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
