package dev.yoonpay.server.api;

import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.lifecycle.EventResponse;
import dev.yoonpay.server.lifecycle.StatusEvents;
import dev.yoonpay.server.payment.CreatePaymentRequest;
import dev.yoonpay.server.payment.PaymentRepository;
import dev.yoonpay.server.payment.PaymentResponse;
import dev.yoonpay.server.payment.PaymentService;
import dev.yoonpay.server.phone.Phones;
import dev.yoonpay.server.refund.CreateRefundRequest;
import dev.yoonpay.server.refund.RefundRepository;
import dev.yoonpay.server.refund.RefundResponse;
import dev.yoonpay.server.refund.RefundService;
import dev.yoonpay.server.web.ApiProblem;
import dev.yoonpay.server.web.IdempotentCall;
import dev.yoonpay.server.web.Page;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.time.Instant;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/v1/payments")
public class PaymentController {

    private final PaymentService service;
    private final PaymentRepository payments;
    private final RefundService refundService;
    private final RefundRepository refunds;
    private final StatusEvents events;
    private final IdempotentCall idempotent;

    public PaymentController(PaymentService service, PaymentRepository payments, RefundService refundService,
                             RefundRepository refunds, StatusEvents events, IdempotentCall idempotent) {
        this.service = service;
        this.payments = payments;
        this.refundService = refundService;
        this.refunds = refunds;
        this.events = events;
        this.idempotent = idempotent;
    }

    @PostMapping
    ResponseEntity<String> create(AppPrincipal app,
                                  @RequestHeader(value = IdempotentCall.HEADER, required = false) String key,
                                  @Valid @RequestBody CreatePaymentRequest body, HttpServletRequest request) {
        return idempotent.run(app, key, request, body, HttpStatus.CREATED,
                () -> PaymentResponse.of(service.create(app, body)));
    }

    @GetMapping("/{id}")
    PaymentResponse get(AppPrincipal app, @PathVariable String id) {
        return PaymentResponse.of(payments.find(app.id(), id).orElseThrow(() -> ApiProblem.notFound("Payment", id)));
    }

    @GetMapping
    Page<PaymentResponse> list(AppPrincipal app,
                               @RequestParam(required = false) String reference,
                               @RequestParam(required = false) String status,
                               @RequestParam(required = false) String provider,
                               @RequestParam(required = false) String method,
                               @RequestParam(name = "created_from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdFrom,
                               @RequestParam(name = "created_to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdTo,
                               @RequestParam(name = "customer_phone", required = false) String customerPhone,
                               @RequestParam(name = "starting_after", required = false) String startingAfter,
                               @RequestParam(required = false) Integer limit) {
        int n = Page.limit(limit);
        String phone = customerPhone == null ? null : Phones.normalizeE164(customerPhone);
        var filter = new PaymentRepository.Filter(reference, status == null ? null : status.toUpperCase(Locale.ROOT),
                provider, method, createdFrom, createdTo, phone);
        return Page.of(payments.list(app.id(), filter, startingAfter, n), n, p -> p.id()).map(PaymentResponse::of);
    }

    @GetMapping("/{id}/events")
    List<EventResponse> events(AppPrincipal app, @PathVariable String id) {
        payments.find(app.id(), id).orElseThrow(() -> ApiProblem.notFound("Payment", id));
        return events.history(app.id(), "payment", id).stream().map(EventResponse::of).toList();
    }

    @PostMapping("/{id}/refunds")
    ResponseEntity<String> refund(AppPrincipal app, @PathVariable String id,
                                  @RequestHeader(value = IdempotentCall.HEADER, required = false) String key,
                                  @Valid @RequestBody CreateRefundRequest body, HttpServletRequest request) {
        return idempotent.run(app, key, request, body, HttpStatus.CREATED,
                () -> RefundResponse.of(refundService.create(app, id, body)));
    }

    @GetMapping("/{id}/refunds")
    List<RefundResponse> refunds(AppPrincipal app, @PathVariable String id) {
        payments.find(app.id(), id).orElseThrow(() -> ApiProblem.notFound("Payment", id));
        return refunds.forPayment(app.id(), id).stream().map(RefundResponse::of).toList();
    }
}
