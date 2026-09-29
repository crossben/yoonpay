package dev.yoonpay.server.api;

import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.lifecycle.EventResponse;
import dev.yoonpay.server.lifecycle.StatusEvents;
import dev.yoonpay.server.refund.RefundRepository;
import dev.yoonpay.server.refund.RefundResponse;
import dev.yoonpay.server.web.ApiProblem;
import dev.yoonpay.server.web.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/v1/refunds")
public class RefundController {

    private final RefundRepository refunds;
    private final StatusEvents events;

    public RefundController(RefundRepository refunds, StatusEvents events) {
        this.refunds = refunds;
        this.events = events;
    }

    @GetMapping("/{id}")
    RefundResponse get(AppPrincipal app, @PathVariable String id) {
        return RefundResponse.of(refunds.find(app.id(), id).orElseThrow(() -> ApiProblem.notFound("Refund", id)));
    }

    @GetMapping
    Page<RefundResponse> list(AppPrincipal app,
                              @RequestParam(required = false) String status,
                              @RequestParam(name = "starting_after", required = false) String startingAfter,
                              @RequestParam(required = false) Integer limit) {
        int n = Page.limit(limit);
        return Page.of(refunds.list(app.id(), status == null ? null : status.toUpperCase(Locale.ROOT), startingAfter, n),
                n, r -> r.id()).map(RefundResponse::of);
    }

    @GetMapping("/{id}/events")
    List<EventResponse> events(AppPrincipal app, @PathVariable String id) {
        refunds.find(app.id(), id).orElseThrow(() -> ApiProblem.notFound("Refund", id));
        return events.history(app.id(), "refund", id).stream().map(EventResponse::of).toList();
    }
}
