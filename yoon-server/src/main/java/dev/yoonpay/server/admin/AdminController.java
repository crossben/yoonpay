package dev.yoonpay.server.admin;

import dev.yoonpay.core.lifecycle.Decision;
import dev.yoonpay.core.lifecycle.IllegalTransitionException;
import dev.yoonpay.core.lifecycle.PayoutStatus;
import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.auth.Applications;
import dev.yoonpay.server.lifecycle.StatusEvents.Cause;
import dev.yoonpay.server.outbox.EventRepository;
import dev.yoonpay.server.outbox.EventResponse;
import dev.yoonpay.server.payout.PayoutRecord;
import dev.yoonpay.server.payout.PayoutRepository;
import dev.yoonpay.server.payout.PayoutResponse;
import dev.yoonpay.server.payout.PayoutTransitions;
import dev.yoonpay.server.web.ApiProblem;
import dev.yoonpay.server.web.Page;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/** Operator API for this instance, across all applications. Read views per application: {@code AdminReadController}; UI: {@code /dashboard} (ADR-0021). */
@RestController
@RequestMapping("/admin/v1")
public class AdminController {

    private final EventRepository events;
    private final PayoutRepository payouts;
    private final PayoutTransitions payoutTransitions;
    private final Applications applications;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public AdminController(EventRepository events, PayoutRepository payouts, PayoutTransitions payoutTransitions,
                           Applications applications, JdbcClient jdbc, ObjectMapper json) {
        this.events = events;
        this.payouts = payouts;
        this.payoutTransitions = payoutTransitions;
        this.applications = applications;
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Events whose delivery gave up. */
    @GetMapping("/dead-letters")
    Page<EventResponse> deadLetters(@RequestParam(name = "starting_after", required = false) String startingAfter,
                                    @RequestParam(required = false) Integer limit) {
        int n = Page.limit(limit);
        return Page.of(events.list(null, null, "DEAD", null, startingAfter, n), n, e -> e.id())
                .map(e -> EventResponse.of(e, json));
    }

    @PostMapping("/dead-letters/{id}/replay")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> replay(@PathVariable String id) {
        String status = jdbc.sql("SELECT delivery_status FROM outbound_events WHERE id = :id")
                .param("id", id).query(String.class).optional()
                .orElseThrow(() -> ApiProblem.notFound("Event", id));
        if (!status.equals("DEAD")) {
            throw ApiProblem.unprocessable("not_dead", "Event " + id + " is " + status.toLowerCase() + ", not dead");
        }
        events.requeue(id);
        return Map.of("id", id, "delivery_status", "pending");
    }

    @GetMapping("/payouts")
    Page<PayoutResponse> payoutsNeedingReview(@RequestParam(name = "needs_review", required = false, defaultValue = "true") boolean needsReview,
                                              @RequestParam(name = "starting_after", required = false) String startingAfter,
                                              @RequestParam(required = false) Integer limit) {
        int n = Page.limit(limit);
        var rows = jdbc.sql("""
                        SELECT * FROM payouts
                        WHERE needs_review = :review AND (CAST(:cursor AS TEXT) IS NULL OR id < :cursor)
                        ORDER BY id DESC LIMIT :limit""")
                .param("review", needsReview).param("cursor", startingAfter).param("limit", n + 1)
                .query(PayoutRecord.class).list();
        return Page.of(rows, n, PayoutRecord::id).map(PayoutResponse::of);
    }

    /** @param status {@code paid} or {@code failed}, as confirmed with the provider out of band */
    public record Resolution(@NotBlank @Pattern(regexp = "paid|failed") String status,
                             @NotBlank @Size(max = 500) String note) {
    }

    /** A human settles a payout after checking the provider's dashboard. Goes through the state machine. */
    @PostMapping("/payouts/{id}/resolve")
    PayoutResponse resolve(@PathVariable String id, @Valid @RequestBody Resolution resolution) {
        PayoutRecord payout = jdbc.sql("SELECT * FROM payouts WHERE id = :id").param("id", id)
                .query(PayoutRecord.class).optional()
                .orElseThrow(() -> ApiProblem.notFound("Payout", id));
        AppPrincipal app = applications.find(payout.applicationId()).orElseThrow();
        PayoutStatus target = resolution.status().equals("paid") ? PayoutStatus.PAID : PayoutStatus.FAILED;
        Map<String, Object> fields = target == PayoutStatus.FAILED
                ? Map.of("needs_review", false, "failure_code", "resolved_failed", "failure_message", resolution.note())
                : Map.of("needs_review", false);
        Decision d;
        try {
            d = payoutTransitions.apply(app, id, target, Cause.admin, null, "resolved by operator: " + resolution.note(), fields);
        } catch (IllegalTransitionException e) {
            throw ApiProblem.unprocessable("illegal_transition", e.getMessage());
        }
        if (d == Decision.IGNORE) {
            throw ApiProblem.unprocessable("already_final", "Payout " + id + " is already " + payout.status().toLowerCase());
        }
        return PayoutResponse.of(payouts.find(app.id(), id).orElseThrow());
    }
}
