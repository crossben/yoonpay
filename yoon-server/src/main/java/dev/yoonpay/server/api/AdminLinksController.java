package dev.yoonpay.server.api;

import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.auth.Applications;
import dev.yoonpay.server.link.CreatePaymentLinkRequest;
import dev.yoonpay.server.link.PaymentLinkResponse;
import dev.yoonpay.server.link.PaymentLinkService;
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
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.UUID;

/**
 * Payment links for the operator (ADR-0030, behind {@code AdminFilter}): list, create and archive,
 * so a merchant without a developer can make links from the dashboard. Nothing here moves money:
 * a link only opens a hosted checkout. Same rules and responses as {@code /v1/payment_links}.
 */
@RestController
@RequestMapping("/admin/v1/applications/{applicationId}/payment_links")
public class AdminLinksController {

    private final Applications applications;
    private final PaymentLinkController links;
    private final PaymentLinkService service;
    private final IdempotentCall idempotent;

    public AdminLinksController(Applications applications, PaymentLinkController links, PaymentLinkService service,
                                IdempotentCall idempotent) {
        this.applications = applications;
        this.links = links;
        this.service = service;
        this.idempotent = idempotent;
    }

    @GetMapping
    Page<PaymentLinkResponse> list(@PathVariable String applicationId,
                                   @RequestParam(required = false) Boolean active,
                                   @RequestParam(name = "starting_after", required = false) String startingAfter,
                                   @RequestParam(required = false) Integer limit) {
        return links.list(app(applicationId), active, startingAfter, limit);
    }

    @PostMapping
    ResponseEntity<String> create(@PathVariable String applicationId,
                                  @RequestHeader(value = IdempotentCall.HEADER, required = false) String key,
                                  @Valid @RequestBody CreatePaymentLinkRequest body, HttpServletRequest request) {
        AppPrincipal app = app(applicationId);
        return idempotent.run(app, key, request, body, HttpStatus.CREATED, () -> service.create(app, body));
    }

    @PostMapping("/{id}/archive")
    ResponseEntity<String> archive(@PathVariable String applicationId, @PathVariable String id,
                                   @RequestHeader(value = IdempotentCall.HEADER, required = false) String key,
                                   HttpServletRequest request) {
        AppPrincipal app = app(applicationId);
        return idempotent.run(app, key, request, java.util.Map.of("archive", id), HttpStatus.OK,
                () -> service.update(app, id, JsonNodeFactory.instance.objectNode().put("active", false)));
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
