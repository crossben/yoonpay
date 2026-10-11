package dev.yoonpay.server.api;

import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.billing.CatalogRepository;
import dev.yoonpay.server.billing.CatalogService;
import dev.yoonpay.server.billing.CreatePlanRequest;
import dev.yoonpay.server.billing.PlanRecord;
import dev.yoonpay.server.billing.PlanResponse;
import dev.yoonpay.server.billing.PriceInput;
import dev.yoonpay.server.web.ApiProblem;
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

import java.util.List;

/** The catalog: plans and their prices (ADR-0025). */
@RestController
public class PlanController {

    public record UpdatePriceRequest(Boolean active) {
    }

    private final CatalogService service;
    private final CatalogRepository catalog;
    private final IdempotentCall idempotent;

    public PlanController(CatalogService service, CatalogRepository catalog, IdempotentCall idempotent) {
        this.service = service;
        this.catalog = catalog;
        this.idempotent = idempotent;
    }

    @PostMapping("/v1/plans")
    ResponseEntity<String> create(AppPrincipal app,
                                  @RequestHeader(value = IdempotentCall.HEADER, required = false) String key,
                                  @Valid @RequestBody CreatePlanRequest body, HttpServletRequest request) {
        return idempotent.run(app, key, request, body, HttpStatus.CREATED, () -> service.createPlan(app, body));
    }

    @GetMapping("/v1/plans")
    Page<PlanResponse> list(AppPrincipal app,
                            @RequestParam(required = false) Boolean active,
                            @RequestParam(name = "starting_after", required = false) String startingAfter,
                            @RequestParam(required = false) Integer limit) {
        int n = Page.limit(limit);
        Page<PlanRecord> page = Page.of(catalog.listPlans(app.id(), active, startingAfter, n), n, PlanRecord::id);
        List<PlanResponse> data = service.withPrices(page.data());
        return new Page<>(data, page.hasMore(), page.nextCursor());
    }

    @GetMapping("/v1/plans/{id}")
    PlanResponse get(AppPrincipal app, @PathVariable String id) {
        return service.getPlan(app, id);
    }

    @PatchMapping("/v1/plans/{id}")
    ResponseEntity<String> update(AppPrincipal app, @PathVariable String id,
                                  @RequestHeader(value = IdempotentCall.HEADER, required = false) String key,
                                  @RequestBody JsonNode body, HttpServletRequest request) {
        return idempotent.run(app, key, request, body, HttpStatus.OK, () -> service.updatePlan(app, id, body));
    }

    @PostMapping("/v1/plans/{id}/prices")
    ResponseEntity<String> createPrice(AppPrincipal app, @PathVariable String id,
                                       @RequestHeader(value = IdempotentCall.HEADER, required = false) String key,
                                       @Valid @RequestBody PriceInput body, HttpServletRequest request) {
        return idempotent.run(app, key, request, body, HttpStatus.CREATED,
                () -> PlanResponse.PriceResponse.of(service.createPrice(app, id, body)));
    }

    @GetMapping("/v1/prices/{id}")
    PlanResponse.PriceResponse getPrice(AppPrincipal app, @PathVariable String id) {
        return PlanResponse.PriceResponse.of(catalog.findPrice(app.id(), id)
                .orElseThrow(() -> ApiProblem.notFound("Price", id)));
    }

    @PatchMapping("/v1/prices/{id}")
    ResponseEntity<String> updatePrice(AppPrincipal app, @PathVariable String id,
                                       @RequestHeader(value = IdempotentCall.HEADER, required = false) String key,
                                       @RequestBody UpdatePriceRequest body, HttpServletRequest request) {
        return idempotent.run(app, key, request, body, HttpStatus.OK,
                () -> PlanResponse.PriceResponse.of(service.setPriceActive(app, id, body.active())));
    }
}
