package dev.yoonpay.server.checkout;

import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.auth.Applications;
import dev.yoonpay.server.lifecycle.StatusEvents.Cause;
import dev.yoonpay.server.payment.PaymentRecord;
import dev.yoonpay.server.payment.PaymentRepository;
import dev.yoonpay.server.payment.PaymentService;
import dev.yoonpay.server.web.ApiProblem;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Currency;
import java.util.List;

/**
 * The public hosted checkout (ADR-0024): the page, its view of the payment and the customer's
 * choice. No API key; the checkout token in the page URL grants this one payment's checkout and
 * nothing else. Unknown id, missing token and wrong token are the same 404.
 */
@Controller
public class CheckoutController {

    public static final String TOKEN_HEADER = "Yoon-Checkout-Token";

    private final PaymentRepository payments;
    private final PaymentService service;
    private final Applications applications;
    private final Clock clock;

    public CheckoutController(PaymentRepository payments, PaymentService service, Applications applications, Clock clock) {
        this.payments = payments;
        this.service = service;
        this.applications = applications;
        this.clock = clock;
    }

    @GetMapping("/checkout/{id:pay_[0-9a-f]+}")
    String page(@PathVariable String id, @RequestParam(name = "t", required = false) String token) {
        authorize(id, token);
        return "forward:/checkout/index.html";
    }

    @GetMapping("/checkout/api/{id}")
    @ResponseBody
    CheckoutView view(@PathVariable String id, @RequestHeader(name = TOKEN_HEADER, required = false) String token) {
        PaymentRecord p = authorize(id, token);
        AppPrincipal app = app(p);
        if (service.expireIfDue(app, p.id(), Cause.api)) {
            p = payments.find(app.id(), p.id()).orElseThrow();
        }
        return view(app, p, null);
    }

    @PostMapping("/checkout/api/{id}/attempts")
    @ResponseBody
    CheckoutView choose(@PathVariable String id, @RequestHeader(name = TOKEN_HEADER, required = false) String token,
                        @Valid @RequestBody CheckoutAttemptRequest body) {
        PaymentRecord p = authorize(id, token);
        AppPrincipal app = app(p);
        if (service.expireIfDue(app, p.id(), Cause.api)) {
            throw new ApiProblem(HttpStatus.CONFLICT, "checkout_expired", "This checkout has expired");
        }
        var result = service.choose(app, p, body.method(), body.phone(), body.piAlias());
        return view(app, payments.find(app.id(), p.id()).orElseThrow(), result.errorCode());
    }

    private CheckoutView view(AppPrincipal app, PaymentRecord p, String lastError) {
        boolean canChoose = p.status().equals("CREATED") && !p.checkoutBusy()
                && p.checkoutExpiresAt() != null && p.checkoutExpiresAt().isAfter(clock.instant());
        List<PaymentService.MethodOption> methods = canChoose
                ? service.collectMethods(app, p.country(), Currency.getInstance(p.currency()), p.checkoutMethod())
                : List.of();
        return CheckoutView.of(p, methods, canChoose, lastError);
    }

    private PaymentRecord authorize(String id, String token) {
        if (token == null || token.isEmpty() || token.length() > 128 || id.length() > 64) {
            throw notFound();
        }
        PaymentRecord p = payments.findHosted(id).orElseThrow(CheckoutController::notFound);
        boolean match = MessageDigest.isEqual(p.checkoutToken().getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
        if (!match) {
            throw notFound();
        }
        return p;
    }

    private AppPrincipal app(PaymentRecord p) {
        return applications.find(p.applicationId()).orElseThrow(CheckoutController::notFound);
    }

    private static ApiProblem notFound() {
        return new ApiProblem(HttpStatus.NOT_FOUND, "resource_not_found", "Checkout not found");
    }
}
