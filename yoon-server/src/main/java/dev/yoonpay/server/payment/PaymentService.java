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
import dev.yoonpay.server.config.CheckoutProperties;
import dev.yoonpay.server.config.YoonProperties;
import dev.yoonpay.core.provider.Capability;
import dev.yoonpay.core.routing.Candidate;
import org.springframework.http.HttpStatus;
import dev.yoonpay.server.lifecycle.StatusEvents;
import dev.yoonpay.server.lifecycle.StatusEvents.Cause;
import dev.yoonpay.server.phone.Phones;
import dev.yoonpay.server.provider.ProviderRegistry;
import dev.yoonpay.server.web.ApiProblem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.ArrayList;
import java.util.Currency;
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
    private final PaymentTransitions transitions;
    private final StatusEvents events;
    private final ProviderRegistry providers;
    private final Router router;
    private final TransactionTemplate tx;
    private final YoonProperties properties;
    private final CheckoutProperties checkout;
    private final Clock clock;

    private static final SecureRandom RANDOM = new SecureRandom();

    public PaymentService(PaymentRepository payments, PaymentTransitions transitions, StatusEvents events,
                          ProviderRegistry providers, Router router, TransactionTemplate tx, YoonProperties properties,
                          CheckoutProperties checkout, Clock clock) {
        this.checkout = checkout;
        this.clock = clock;
        this.payments = payments;
        this.transitions = transitions;
        this.events = events;
        this.providers = providers;
        this.router = router;
        this.tx = tx;
        this.properties = properties;
    }

    public PaymentRecord create(AppPrincipal app, CreatePaymentRequest req) {
        if (req.hosted()) {
            return createHosted(app, req);
        }
        if (req.method() == null) {
            throw ApiProblem.invalid("method is required unless checkout is hosted");
        }
        Currency currency = currency(req.currency());
        String phone = phone(req);
        String alias = alias(req.customer() == null ? null : req.customer().piAlias());
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
                    phone, req.returnUrl(), null, null, null, null, null, null, null, 0, null, null, 0, alias,
                    PaymentRecord.DIRECT, null, null, null, null, false));
            events.record(app.id(), "payment", id, null, PaymentStatus.CREATED, Decision.APPLY, Cause.api, null, null);
        });

        Round round = attemptRound(app, id, route, new Money(req.amount(), currency), req.country(), req.method(),
                phone, req.description(), returnUrl, alias);
        if (round instanceof Round.AllRejected(CallOutcome.Rejected rejected, List<String> notes)) {
            move(app, id, PaymentStatus.FAILED, rejected.code(), "rejected by every provider tried", Map.of(
                    "failure_code", rejected.code().toLowerCase(),
                    "failure_message", rejected.message(),
                    "routing_reason", String.join("; ", notes)));
        }
        return payments.find(app.id(), id).orElseThrow();
    }

    /** How one attempt round (route → providers in order, failover only after Rejected) ended. */
    sealed interface Round {
        /** Accepted or Unknown: the payment is PENDING on one provider; no other may be tried. */
        record WithProvider() implements Round {
        }

        /** Every provider definitely refused; nothing is in flight. */
        record AllRejected(CallOutcome.Rejected last, List<String> notes) implements Round {
        }
    }

    /**
     * The failover loop, shared by direct payments and hosted checkout rounds. The caller must
     * guarantee that no other round runs for this payment (direct: the payment is new; hosted:
     * the checkout claim).
     */
    private Round attemptRound(AppPrincipal app, String id, Router.Decision.Route route, Money amount, String country,
                               String method, String phone, String description, URI returnUrl, String alias) {
        List<String> notes = new ArrayList<>(List.of(route.reason()));
        CallOutcome.Rejected lastRejection = null;

        for (ProviderId providerId : route.providers()) {
            PaymentProvider provider = providers.find(app, providerId.value()).orElseThrow();
            String attempt = Ids.attempt();
            payments.insertAttempt(attempt, id, providerId.value());

            CollectRequest call = new CollectRequest(attempt, amount, country, method, phone, description, returnUrl,
                    callbackUrl(app, providerId), alias);
            CallOutcome outcome = providers.call(app, providerId.value(), Operation.COLLECT, () -> provider.collect(call));

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
                    return new Round.WithProvider();
                }
                case CallOutcome.Unknown u -> {
                    // Possibly accepted: never try another provider (double charge risk).
                    String ref = u.reference() == null ? "" : u.reference().value();
                    payments.completeAttempt(attempt, "UNKNOWN", u.reference() == null ? null : ref, null, u.cause());
                    notes.add("outcome unknown at " + providerId + " (" + u.cause() + "); awaiting provider status, no failover");
                    move(app, id, PaymentStatus.PENDING, null, "provider outcome unknown: " + u.cause(), Map.of(
                            "provider", providerId.value(),
                            "provider_reference", ref,
                            "routing_reason", String.join("; ", notes)));
                    return new Round.WithProvider();
                }
                case CallOutcome.Rejected r -> {
                    payments.completeAttempt(attempt, "REJECTED", null, r.code(), r.message());
                    notes.add("rejected by " + providerId + " (" + r.code() + ")");
                    lastRejection = r;
                }
            }
        }
        return new Round.AllRejected(lastRejection, notes);
    }

    // ------------------------------------------------------------------ hosted checkout (ADR-0024)

    /** Attempts (all rounds together) after which a hosted checkout gives up. */
    public static final int MAX_CHECKOUT_ATTEMPTS = 10;
    public static final String PI_ALIAS_METHOD = "pispi";

    /** A method the checkout page may offer. {@code needs}: {@code none} or {@code pi_alias}. */
    public record MethodOption(String method, boolean available, String needs) {
    }

    /** The outcome of a customer's choice: null error = a provider has the payment (or it failed for good). */
    public record ChoiceResult(String errorCode) {
    }

    private PaymentRecord createHosted(AppPrincipal app, CreatePaymentRequest req) {
        if (req.provider() != null) {
            throw ApiProblem.invalid("provider cannot be combined with checkout hosted");
        }
        Currency currency = currency(req.currency());
        String phone = phone(req);
        String alias = alias(req.customer() == null ? null : req.customer().piAlias());
        uri(req.returnUrl());
        if (properties.publicUrl() == null) {
            throw ApiProblem.unprocessable("public_url_required",
                    "Hosted checkout needs YOON_PUBLIC_URL: the checkout page URL must be absolute");
        }
        if (collectMethods(app, req.country(), currency, req.method()).isEmpty()) {
            throw ApiProblem.unprocessable(Router.NO_PROVIDER_FOR_METHOD, "No configured provider collects "
                    + (req.method() == null ? "" : "by " + req.method() + " ") + "in " + req.country() + " (" + currency + ")");
        }

        String id = Ids.payment();
        String token = newToken();
        String url = properties.publicUrl().resolve("/checkout/" + id + "?t=" + token).toString();
        Instant expiresAt = clock.instant().plus(checkout.ttl());
        tx.executeWithoutResult(s -> {
            payments.insert(new PaymentRecord(id, app.id(), PaymentStatus.CREATED.name(), req.amount(),
                    currency.getCurrencyCode(), req.country(), null, req.reference(), req.description(),
                    phone, req.returnUrl(), null, null, null, null, null, null, null, 0, null, null, 0, alias,
                    PaymentRecord.HOSTED, token, url, expiresAt, req.method(), false));
            events.record(app.id(), "payment", id, null, PaymentStatus.CREATED, Decision.APPLY, Cause.api, null,
                    "hosted checkout: waiting for the customer's choice");
        });
        return payments.find(app.id(), id).orElseThrow();
    }

    /**
     * The methods this application's providers collect in {@code country}/{@code currency}, sorted.
     * {@code only}: the one method the application allowed, or null.
     */
    public List<MethodOption> collectMethods(AppPrincipal app, String country, Currency currency, String only) {
        Map<String, Boolean> methods = new TreeMap<>();
        for (Candidate c : providers.candidates(app)) {
            for (Capability cap : c.capabilities().supported()) {
                if (cap.operation() == Operation.COLLECT && cap.country().equals(country)
                        && cap.currency().equals(currency) && (only == null || only.equals(cap.method()))) {
                    methods.merge(cap.method(), c.available(), Boolean::logicalOr);
                }
            }
        }
        return methods.entrySet().stream()
                .map(e -> new MethodOption(e.getKey(), e.getValue(), PI_ALIAS_METHOD.equals(e.getKey()) ? "pi_alias" : "none"))
                .toList();
    }

    /**
     * The customer's choice on the hosted page: claim the checkout (one round at a time, never
     * while a provider may have it), run one round through the normal routing and failover, and
     * either leave the payment with a provider (PENDING) or release it for another choice.
     */
    public ChoiceResult choose(AppPrincipal app, PaymentRecord p, String method, String rawPhone, String rawAlias) {
        Currency currency = Currency.getInstance(p.currency());
        boolean offered = collectMethods(app, p.country(), currency, p.checkoutMethod()).stream()
                .anyMatch(m -> m.method().equals(method));
        if (!offered) {
            throw ApiProblem.unprocessable("method_unavailable", "This method is not offered for this payment");
        }
        String phone = rawPhone == null || rawPhone.isBlank() ? null : Phones.normalize(rawPhone.trim(), p.country());
        String alias = alias(rawAlias);
        var decision = router.route(new RouteRequest(Operation.COLLECT, p.country(), method, currency, null),
                providers.candidates(app));
        if (!(decision instanceof Router.Decision.Route route)) {
            throw ApiProblem.unprocessable("method_unavailable", "No provider can take this method right now");
        }

        if (!payments.claimCheckout(p.id(), method, phone, alias)) {
            PaymentRecord now = payments.find(app.id(), p.id()).orElseThrow();
            if (!now.status().equals(PaymentStatus.CREATED.name())) {
                throw new ApiProblem(HttpStatus.CONFLICT, "checkout_not_open", "This payment no longer waits for a choice");
            }
            if (now.checkoutBusy()) {
                throw new ApiProblem(HttpStatus.CONFLICT, "checkout_in_progress", "A payment attempt is already running");
            }
            throw new ApiProblem(HttpStatus.CONFLICT, "checkout_expired", "This checkout has expired");
        }

        // From here the claim is ours. An unexpected exception leaves it taken: the reconciler
        // decides (unknown attempt → PENDING), never a second customer click.
        PaymentRecord claimed = payments.find(app.id(), p.id()).orElseThrow();
        Round round = attemptRound(app, p.id(), route, new Money(p.amount(), currency), p.country(), method,
                claimed.customerPhone(), p.description(), URI.create(p.hostedCheckoutUrl()), claimed.customerPiAlias());
        if (round instanceof Round.AllRejected(CallOutcome.Rejected rejected, List<String> notes)) {
            if (payments.countAttempts(p.id()) >= MAX_CHECKOUT_ATTEMPTS) {
                move(app, p.id(), PaymentStatus.FAILED, rejected.code(), "hosted checkout: too many refused attempts", Map.of(
                        "failure_code", "checkout_attempts_exhausted",
                        "failure_message", "Every attempt was refused; last: " + rejected.code(),
                        "routing_reason", String.join("; ", notes)));
            } else {
                payments.releaseCheckout(p.id());
            }
            return new ChoiceResult(rejected.code());
        }
        return new ChoiceResult(null);
    }

    /**
     * Fails a hosted checkout nobody used in time ({@code checkout_expired}). Locks the row first, so
     * it cannot race a claim: a claimed (busy) or already-moved payment is left alone.
     */
    public boolean expireIfDue(AppPrincipal app, String id, Cause cause) {
        Boolean expired = tx.execute(s -> {
            PaymentRecord p = payments.lock(app.id(), id).orElseThrow();
            if (!p.hosted() || !p.status().equals(PaymentStatus.CREATED.name()) || p.checkoutBusy()
                    || p.checkoutExpiresAt() == null || p.checkoutExpiresAt().isAfter(clock.instant())) {
                return false;
            }
            transitions.apply(app, id, PaymentStatus.FAILED, cause, null, "hosted checkout expired unused", Map.of(
                    "failure_code", "checkout_expired",
                    "failure_message", "The customer did not choose a payment method in time"));
            return true;
        });
        return Boolean.TRUE.equals(expired);
    }

    /** A claimed checkout left behind by a crash: an attempt that may have been sent is an unknown outcome. */
    public void recoverInterruptedCheckout(AppPrincipal app, PaymentRecord p) {
        List<String> unresolved = payments.unresolvedAttempts(p.id());
        if (unresolved.isEmpty()) {
            payments.releaseCheckout(p.id());
            return;
        }
        String provider = payments.attemptProvider(unresolved.getFirst());
        Map<String, Object> fields = new HashMap<>();
        fields.put("provider", provider);
        move(app, p.id(), PaymentStatus.PENDING, null,
                "hosted checkout interrupted after contacting " + provider + "; outcome unknown", fields);
    }

    private static String newToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String phone(CreatePaymentRequest req) {
        return req.customer() == null || req.customer().phone() == null
                ? null : Phones.normalize(req.customer().phone(), req.country());
    }

    private static String alias(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    private void move(AppPrincipal app, String id, PaymentStatus to, String raw, String detail, Map<String, Object> fields) {
        transitions.apply(app, id, to, Cause.provider_call, raw, detail, fields);
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
