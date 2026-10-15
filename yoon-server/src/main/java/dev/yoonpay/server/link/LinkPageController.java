package dev.yoonpay.server.link;

import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.auth.Applications;
import dev.yoonpay.server.payment.CreatePaymentRequest;
import dev.yoonpay.server.payment.PaymentRecord;
import dev.yoonpay.server.payment.PaymentService;
import dev.yoonpay.server.web.ApiProblem;
import dev.yoonpay.server.web.IdempotentCall;
import io.nayuki.qrcodegen.QrCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.Clock;

/**
 * The public side of payment links (ADR-0030): the page a customer opens, what it may know, and
 * "Pay", which creates an ordinary hosted payment and sends the customer to its checkout. No API
 * key: the unguessable code is the address. Under {@code CheckoutFilter} (CSP, no cache, rate limit).
 */
@Controller
public class LinkPageController {

    private static final String CODE = "{code}";
    private static final java.util.regex.Pattern CODE_FORMAT = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{22}");

    /** What the page shows. {@code state}: open, inactive, expired or used_up. */
    public record LinkView(String merchant, String description, Long amount, Long minAmount, Long maxAmount,
                           String currency, String state) {
    }

    /** {@code attempt}: random per page view, so a double click or a retry pays one payment. */
    public record PayRequest(@Positive Long amount, @NotNull @Pattern(regexp = "[A-Za-z0-9-]{8,64}") String attempt) {
    }

    public record PayView(String checkoutUrl) {
    }

    private final PaymentLinkRepository links;
    private final Applications applications;
    private final PaymentService payments;
    private final IdempotentCall idempotent;
    private final Clock clock;
    private final dev.yoonpay.server.config.YoonProperties properties;

    public LinkPageController(PaymentLinkRepository links, Applications applications, PaymentService payments,
                              IdempotentCall idempotent, Clock clock, dev.yoonpay.server.config.YoonProperties properties) {
        this.properties = properties;
        this.links = links;
        this.applications = applications;
        this.payments = payments;
        this.idempotent = idempotent;
        this.clock = clock;
    }

    @GetMapping("/l/" + CODE)
    String page(@PathVariable String code) {
        find(code);
        return "forward:/checkout/link.html";
    }

    @GetMapping("/checkout/api/links/" + CODE)
    @ResponseBody
    LinkView view(@PathVariable String code) {
        PaymentLinkRecord l = find(code);
        return new LinkView(app(l).name(), l.description(), l.amount(), l.minAmount(), l.maxAmount(), l.currency(), state(l));
    }

    @PostMapping("/checkout/api/links/" + CODE + "/payments")
    ResponseEntity<String> pay(@PathVariable String code, @Valid @RequestBody PayRequest body, HttpServletRequest request) {
        PaymentLinkRecord l = find(code);
        AppPrincipal app = app(l);
        return idempotent.run(app, "link:" + l.id() + ":" + body.attempt(), request, body, HttpStatus.CREATED, () -> {
            String state = state(l);
            if (!state.equals("open")) {
                throw new ApiProblem(HttpStatus.CONFLICT, "link_" + state, "This payment link is " + state.replace('_', ' '));
            }
            long amount = amount(l, body.amount());
            PaymentRecord p = payments.createForLink(app, new CreatePaymentRequest(amount, l.currency(), l.country(),
                    l.method(), null, l.id(), l.description(), l.returnUrl(), null, "hosted"), l.id());
            return new PayView(p.hostedCheckoutUrl());
        });
    }

    @GetMapping(value = "/l/" + CODE + "/qr.svg", produces = "image/svg+xml")
    @ResponseBody
    ResponseEntity<String> qr(@PathVariable String code) {
        find(code);
        // The public address (behind a proxy the request URL is the internal one).
        String url = properties.publicUrl() == null ? "/l/" + code : properties.publicUrl().resolve("/l/" + code).toString();
        return ResponseEntity.ok().contentType(MediaType.valueOf("image/svg+xml")).body(svg(QrCode.encodeText(url, QrCode.Ecc.MEDIUM)));
    }

    private long amount(PaymentLinkRecord l, Long chosen) {
        if (l.amount() != null) {
            if (chosen != null && chosen.longValue() != l.amount()) {
                throw ApiProblem.invalid("this link has a fixed amount");
            }
            return l.amount();
        }
        if (chosen == null) {
            throw ApiProblem.invalid("enter the amount to pay");
        }
        if ((l.minAmount() != null && chosen < l.minAmount()) || (l.maxAmount() != null && chosen > l.maxAmount())) {
            throw ApiProblem.invalid("the amount is outside what this link accepts");
        }
        return chosen;
    }

    private String state(PaymentLinkRecord l) {
        if (!l.active()) {
            return "inactive";
        }
        if (l.expiresAt() != null && !clock.instant().isBefore(l.expiresAt())) {
            return "expired";
        }
        if (l.maxPayments() != null && links.stats(l.id()).paymentsCount() >= l.maxPayments()) {
            return "used_up";
        }
        return "open";
    }

    private PaymentLinkRecord find(String code) {
        if (code == null || !CODE_FORMAT.matcher(code).matches()) {
            throw new ApiProblem(HttpStatus.NOT_FOUND, "resource_not_found", "Payment link not found");
        }
        return links.findByCode(code).orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND, "resource_not_found",
                "Payment link not found"));
    }

    private AppPrincipal app(PaymentLinkRecord l) {
        return applications.find(l.applicationId()).orElseThrow();
    }

    /** One path per dark module, with a quiet zone of 4 modules. */
    static String svg(QrCode qr) {
        int border = 4;
        int size = qr.size + border * 2;
        StringBuilder path = new StringBuilder();
        for (int y = 0; y < qr.size; y++) {
            for (int x = 0; x < qr.size; x++) {
                if (qr.getModule(x, y)) {
                    path.append('M').append(x + border).append(',').append(y + border).append("h1v1h-1z");
                }
            }
        }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + size + " " + size
                + "\" shape-rendering=\"crispEdges\"><rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>"
                + "<path d=\"" + path + "\" fill=\"#000000\"/></svg>";
    }
}
