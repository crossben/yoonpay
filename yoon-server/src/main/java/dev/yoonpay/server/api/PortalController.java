package dev.yoonpay.server.api;

import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.portal.CreatePortalSessionRequest;
import dev.yoonpay.server.portal.PortalService;
import dev.yoonpay.server.web.IdempotentCall;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Customer portal sessions (ADR-0031): the merchant's server asks for one; the customer opens its URL. */
@RestController
public class PortalController {

    private final PortalService portal;
    private final IdempotentCall idempotent;

    public PortalController(PortalService portal, IdempotentCall idempotent) {
        this.portal = portal;
        this.idempotent = idempotent;
    }

    @PostMapping("/v1/customers/{id}/portal_sessions")
    ResponseEntity<String> create(AppPrincipal app, @PathVariable String id,
                                  @RequestHeader(value = IdempotentCall.HEADER, required = false) String key,
                                  @Valid @RequestBody CreatePortalSessionRequest body, HttpServletRequest request) {
        return idempotent.run(app, key, request, body, HttpStatus.CREATED, () -> portal.create(app, id, body));
    }
}
