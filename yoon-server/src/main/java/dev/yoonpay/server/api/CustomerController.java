package dev.yoonpay.server.api;

import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.billing.CreateCustomerRequest;
import dev.yoonpay.server.billing.CustomerRecord;
import dev.yoonpay.server.billing.CustomerRepository;
import dev.yoonpay.server.billing.CustomerResponse;
import dev.yoonpay.server.billing.CustomerService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Customers for billing (ADR-0025). */
@RestController
@RequestMapping("/v1/customers")
public class CustomerController {

    private final CustomerService service;
    private final CustomerRepository customers;
    private final IdempotentCall idempotent;
    private final ObjectMapper json;

    public CustomerController(CustomerService service, CustomerRepository customers, IdempotentCall idempotent,
                              ObjectMapper json) {
        this.service = service;
        this.customers = customers;
        this.idempotent = idempotent;
        this.json = json;
    }

    @PostMapping
    ResponseEntity<String> create(AppPrincipal app,
                                  @RequestHeader(value = IdempotentCall.HEADER, required = false) String key,
                                  @Valid @RequestBody CreateCustomerRequest body, HttpServletRequest request) {
        return idempotent.run(app, key, request, body, HttpStatus.CREATED,
                () -> CustomerResponse.of(service.create(app, body), json));
    }

    @GetMapping("/{id}")
    CustomerResponse get(AppPrincipal app, @PathVariable String id) {
        return CustomerResponse.of(customers.find(app.id(), id).orElseThrow(() -> ApiProblem.notFound("Customer", id)), json);
    }

    @PatchMapping("/{id}")
    ResponseEntity<String> update(AppPrincipal app, @PathVariable String id,
                                  @RequestHeader(value = IdempotentCall.HEADER, required = false) String key,
                                  @RequestBody JsonNode body, HttpServletRequest request) {
        return idempotent.run(app, key, request, body, HttpStatus.OK,
                () -> CustomerResponse.of(service.update(app, id, body), json));
    }

    @GetMapping
    Page<CustomerResponse> list(AppPrincipal app,
                                @RequestParam(name = "external_id", required = false) String externalId,
                                @RequestParam(name = "starting_after", required = false) String startingAfter,
                                @RequestParam(required = false) Integer limit) {
        int n = Page.limit(limit);
        return Page.of(customers.list(app.id(), externalId, startingAfter, n), n, CustomerRecord::id)
                .map(c -> CustomerResponse.of(c, json));
    }
}
