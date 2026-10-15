package dev.yoonpay.server.api;

import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.link.CreatePaymentLinkRequest;
import dev.yoonpay.server.link.PaymentLinkRecord;
import dev.yoonpay.server.link.PaymentLinkRepository;
import dev.yoonpay.server.link.PaymentLinkResponse;
import dev.yoonpay.server.link.PaymentLinkService;
import dev.yoonpay.server.web.IdempotentCall;
import dev.yoonpay.server.web.Page;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Payment links (ADR-0030). */
@RestController
public class PaymentLinkController {

    private final PaymentLinkService service;
    private final PaymentLinkRepository links;
    private final IdempotentCall idempotent;

    public PaymentLinkController(PaymentLinkService service, PaymentLinkRepository links, IdempotentCall idempotent) {
        this.service = service;
        this.links = links;
        this.idempotent = idempotent;
    }

    @PostMapping("/v1/payment_links")
    ResponseEntity<String> create(AppPrincipal app,
                                  @RequestHeader(value = IdempotentCall.HEADER, required = false) String key,
                                  @Valid @RequestBody CreatePaymentLinkRequest body, HttpServletRequest request) {
        return idempotent.run(app, key, request, body, HttpStatus.CREATED, () -> service.create(app, body));
    }

    @GetMapping("/v1/payment_links")
    Page<PaymentLinkResponse> list(AppPrincipal app,
                                   @RequestParam(required = false) Boolean active,
                                   @RequestParam(name = "starting_after", required = false) String startingAfter,
                                   @RequestParam(required = false) Integer limit) {
        int n = Page.limit(limit);
        Page<PaymentLinkRecord> page = Page.of(links.list(app.id(), active, startingAfter, n), n, PaymentLinkRecord::id);
        return new Page<>(service.views(page.data()), page.hasMore(), page.nextCursor());
    }

    @GetMapping("/v1/payment_links/{id}")
    PaymentLinkResponse get(AppPrincipal app, @PathVariable String id) {
        return service.get(app, id);
    }

    @PatchMapping("/v1/payment_links/{id}")
    ResponseEntity<String> update(AppPrincipal app, @PathVariable String id,
                                  @RequestHeader(value = IdempotentCall.HEADER, required = false) String key,
                                  @RequestBody JsonNode body, HttpServletRequest request) {
        return idempotent.run(app, key, request, body, HttpStatus.OK, () -> service.update(app, id, body));
    }
}
