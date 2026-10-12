package dev.yoonpay.server.api;

import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.billing.CouponRecord;
import dev.yoonpay.server.billing.CouponRepository;
import dev.yoonpay.server.billing.CouponResponse;
import dev.yoonpay.server.billing.CouponService;
import dev.yoonpay.server.billing.CreateCouponRequest;
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

/** Coupons (ADR-0029). */
@RestController
public class CouponController {

    private final CouponService service;
    private final CouponRepository coupons;
    private final IdempotentCall idempotent;

    public CouponController(CouponService service, CouponRepository coupons, IdempotentCall idempotent) {
        this.service = service;
        this.coupons = coupons;
        this.idempotent = idempotent;
    }

    @PostMapping("/v1/coupons")
    ResponseEntity<String> create(AppPrincipal app,
                                  @RequestHeader(value = IdempotentCall.HEADER, required = false) String key,
                                  @Valid @RequestBody CreateCouponRequest body, HttpServletRequest request) {
        return idempotent.run(app, key, request, body, HttpStatus.CREATED, () -> service.create(app, body));
    }

    @GetMapping("/v1/coupons")
    Page<CouponResponse> list(AppPrincipal app,
                              @RequestParam(required = false) String code,
                              @RequestParam(required = false) Boolean active,
                              @RequestParam(name = "starting_after", required = false) String startingAfter,
                              @RequestParam(required = false) Integer limit) {
        int n = Page.limit(limit);
        Page<CouponRecord> page = Page.of(coupons.list(app.id(), code, active, startingAfter, n), n, CouponRecord::id);
        return new Page<>(service.list(page.data()), page.hasMore(), page.nextCursor());
    }

    @GetMapping("/v1/coupons/{id}")
    CouponResponse get(AppPrincipal app, @PathVariable String id) {
        return service.get(app, id);
    }

    @PatchMapping("/v1/coupons/{id}")
    ResponseEntity<String> update(AppPrincipal app, @PathVariable String id,
                                  @RequestHeader(value = IdempotentCall.HEADER, required = false) String key,
                                  @RequestBody JsonNode body, HttpServletRequest request) {
        return idempotent.run(app, key, request, body, HttpStatus.OK, () -> service.update(app, id, body));
    }
}
