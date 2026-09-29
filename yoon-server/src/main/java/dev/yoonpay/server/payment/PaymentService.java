package dev.yoonpay.server.payment;

import dev.yoonpay.core.id.Ids;
import dev.yoonpay.core.lifecycle.Decision;
import dev.yoonpay.core.lifecycle.PaymentStatus;
import dev.yoonpay.core.money.Money;
import dev.yoonpay.core.provider.CallOutcome;
import dev.yoonpay.core.provider.CollectRequest;
import dev.yoonpay.core.provider.Operation;
import dev.yoonpay.core.provider.PaymentProvider;
import dev.yoonpay.core.provider.ProviderId;
import dev.yoonpay.core.routing.RouteRequest;
import dev.yoonpay.core.routing.Router;
import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.config.YoonProperties;
import dev.yoonpay.server.lifecycle.StatusEvents;
import dev.yoonpay.server.lifecycle.StatusEvents.Cause;
import dev.yoonpay.server.phone.Phones;
import dev.yoonpay.server.provider.ProviderRegistry;
import dev.yoonpay.server.web.ApiProblem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates collections. The failover rule lives here: the next provider is tried only after a
 * definite rejection. An unknown outcome (timeout, 5xx) stops routing — the payment stays
 * PENDING on that provider until its status API answers, because the customer may already be
 * paying there.
 *
 * <p>Provider calls happen outside database transactions; every state change is a short
 * transaction that locks the row and asks the state machine.
 */
@Service
public class PaymentService {

    private final PaymentRepository payments;
    private final StatusEvents events;
    private final ProviderRegistry providers;
    private final Router router;
    private final TransactionTemplate tx;
    private final YoonProperties properties;

    public PaymentService(PaymentRepository payments, StatusEvents events, ProviderRegistry providers,
                          Router router, TransactionTemplate tx, YoonProperties properties) {
        this.payments = payments;
        this.events = events;
        this.providers = providers;
        this.router = router;
        this.tx = tx;
        this.properties = properties;
    }

    public PaymentRecord create(AppPrincipal app, CreatePaymentRequest req) {
        Currency currency = currency(req.currency());
        String phone = req.customer() == null || req.customer().phone() == null
                ? null : Phones.normalize(req.customer().phone(), req.country());
        URI returnUrl = uri(req.returnUrl());

        var decision = router.route(new RouteRequest(Operation.COLLECT, req.country(), req.method(), currency,
                req.provider() == null ? null : providerId(req.provider())), providers.candidates(app));
        if (decision instanceof Router.Decision.NoRoute(String code, String message)) {
            throw ApiProblem.unprocessable(code, message);
        }
        var route = (Router.Decision.Route) decision;

        String id = Ids.payment();
        tx.executeWithoutResult(s -> {
            payments.insert(new PaymentRecord(id, app.id(), PaymentStatus.CREATED.name(), req.amount(),
                    currency.getCurrencyCode(), req.country(), req.method(), req.reference(), req.description(),
                    phone, req.returnUrl(), null, null, null, null, null, null, null, 0, null, null));
            events.record(app.id(), "payment", id, null, PaymentStatus.CREATED, Decision.APPLY, Cause.api, null, null);
        });

        List<String> notes = new ArrayList<>(List.of(route.reason()));
        CallOutcome.Rejected lastRejection = null;

        for (ProviderId providerId : route.providers()) {
            PaymentProvider provider = providers.find(app, providerId.value()).orElseThrow();
            String attempt = Ids.attempt();
            payments.insertAttempt(attempt, id, providerId.value());

            CollectRequest call = new CollectRequest(attempt, new Money(req.amount(), currency), req.country(),
                    req.method(), phone, req.description(), returnUrl, callbackUrl(app, providerId));
            CallOutcome outcome = providers.call(app, providerId.value(), () -> provider.collect(call));

            switch (outcome) {
                case CallOutcome.Accepted a -> {
                    payments.completeAttempt(attempt, "ACCEPTED", a.reference().value(), null, null);
                    notes.add("accepted by " + providerId);
                    move(app, id, PaymentStatus.PENDING, null, "accepted by provider", Map.of(
                            "provider", providerId.value(),
                            "provider_reference", a.reference().value(),
                            "checkout_url", a.checkoutUrl() == null ? "" : a.checkoutUrl().toString(),
                            "instructions", a.instructions() == null ? "" : a.instructions(),
                            "routing_reason", String.join("; ", notes)));
                    return payments.find(app.id(), id).orElseThrow();
                }
                case CallOutcome.Unknown u -> {
                    // Possibly accepted: never try another provider (double charge risk).
                    payments.completeAttempt(attempt, "UNKNOWN", null, null, u.cause());
                    notes.add("outcome unknown at " + providerId + " (" + u.cause() + "); awaiting provider status, no failover");
                    move(app, id, PaymentStatus.PENDING, null, "provider outcome unknown: " + u.cause(), Map.of(
                            "provider", providerId.value(),
                            "routing_reason", String.join("; ", notes)));
                    return payments.find(app.id(), id).orElseThrow();
                }
                case CallOutcome.Rejected r -> {
                    payments.completeAttempt(attempt, "REJECTED", null, r.code(), r.message());
                    notes.add("rejected by " + providerId + " (" + r.code() + ")");
                    lastRejection = r;
                }
            }
        }

        CallOutcome.Rejected rejected = lastRejection;
        move(app, id, PaymentStatus.FAILED, rejected.code(), "rejected by every provider tried", Map.of(
                "failure_code", rejected.code().toLowerCase(),
                "failure_message", rejected.message(),
                "routing_reason", String.join("; ", notes)));
        return payments.find(app.id(), id).orElseThrow();
    }

    /** Lock, ask the state machine, record the event, apply. */
    private void move(AppPrincipal app, String id, PaymentStatus to, String raw, String detail, Map<String, Object> fields) {
        tx.executeWithoutResult(s -> {
            PaymentRecord current = payments.lock(app.id(), id).orElseThrow();
            PaymentStatus from = PaymentStatus.valueOf(current.status());
            Decision decision = from.decide(to);
            events.record(app.id(), "payment", id, from, to, decision, Cause.provider_call, raw, detail);
            if (decision == Decision.APPLY) {
                Map<String, Object> clean = new HashMap<>(fields);
                clean.replaceAll((k, v) -> "".equals(v) ? null : v);
                payments.update(id, to.name(), clean);
            }
        });
    }

    private URI callbackUrl(AppPrincipal app, ProviderId provider) {
        return properties.publicUrl() == null ? null
                : properties.publicUrl().resolve("/v1/hooks/" + provider + "/" + app.id());
    }

    static Currency currency(String code) {
        try {
            return Currency.getInstance(code);
        } catch (IllegalArgumentException e) {
            throw ApiProblem.invalid("Unknown currency " + code);
        }
    }

    static ProviderId providerId(String value) {
        try {
            return new ProviderId(value);
        } catch (IllegalArgumentException e) {
            throw ApiProblem.invalid("Invalid provider " + value);
        }
    }

    private static URI uri(String value) {
        if (value == null) {
            return null;
        }
        try {
            URI u = URI.create(value);
            if (u.getScheme() == null || !(u.getScheme().equals("https") || u.getScheme().equals("http"))) {
                throw ApiProblem.invalid("return_url must be an http(s) URL");
            }
            return u;
        } catch (IllegalArgumentException e) {
            throw ApiProblem.invalid("return_url must be an http(s) URL");
        }
    }
}
