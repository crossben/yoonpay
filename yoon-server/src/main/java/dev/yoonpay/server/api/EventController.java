package dev.yoonpay.server.api;

import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.outbox.EventRepository;
import dev.yoonpay.server.outbox.EventResponse;
import dev.yoonpay.server.web.ApiProblem;
import dev.yoonpay.server.web.IdempotentCall;
import dev.yoonpay.server.web.Page;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.Map;

/**
 * The events Yoon generated for this application — also a way to catch up after downtime
 * instead of depending only on webhook delivery.
 */
@RestController
@RequestMapping("/v1/events")
public class EventController {

    private final EventRepository events;
    private final IdempotentCall idempotent;
    private final ObjectMapper json;

    public EventController(EventRepository events, IdempotentCall idempotent, ObjectMapper json) {
        this.events = events;
        this.idempotent = idempotent;
        this.json = json;
    }

    @GetMapping
    Page<EventResponse> list(AppPrincipal app,
                             @RequestParam(required = false) String type,
                             @RequestParam(name = "delivery_status", required = false) String deliveryStatus,
                             @RequestParam(name = "resource_id", required = false) String resourceId,
                             @RequestParam(name = "starting_after", required = false) String startingAfter,
                             @RequestParam(required = false) Integer limit) {
        int n = Page.limit(limit);
        return Page.of(events.list(app.id(), type,
                        deliveryStatus == null ? null : deliveryStatus.toUpperCase(Locale.ROOT), resourceId, startingAfter, n),
                n, e -> e.id()).map(e -> EventResponse.of(e, json));
    }

    @GetMapping("/{id}")
    EventResponse get(AppPrincipal app, @PathVariable String id) {
        return EventResponse.of(events.find(app.id(), id).orElseThrow(() -> ApiProblem.notFound("Event", id)), json);
    }

    @PostMapping("/{id}/redeliver")
    ResponseEntity<String> redeliver(AppPrincipal app, @PathVariable String id,
                                     @RequestHeader(value = IdempotentCall.HEADER, required = false) String key,
                                     HttpServletRequest request) {
        return idempotent.run(app, key, request, Map.of("event", id), HttpStatus.ACCEPTED, () -> {
            events.find(app.id(), id).orElseThrow(() -> ApiProblem.notFound("Event", id));
            if (!events.requeue(id)) {
                throw ApiProblem.unprocessable("no_webhook_endpoint",
                        "This application has no webhook URL configured (YOON_APPS_<APP>_WEBHOOK_URL)");
            }
            return EventResponse.of(events.find(app.id(), id).orElseThrow(), json);
        });
    }
}
