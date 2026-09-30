package dev.yoonpay.server.payout;

import dev.yoonpay.core.id.Ids;
import dev.yoonpay.core.lifecycle.Decision;
import dev.yoonpay.core.lifecycle.PayoutStatus;
import dev.yoonpay.core.money.Money;
import dev.yoonpay.core.provider.CallOutcome;
import dev.yoonpay.core.provider.Operation;
import dev.yoonpay.core.provider.PaymentProvider;
import dev.yoonpay.core.provider.PayoutRequest;
import dev.yoonpay.core.provider.ProviderId;
import dev.yoonpay.core.routing.RouteRequest;
import dev.yoonpay.core.routing.Router;
import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.lifecycle.StatusEvents;
import dev.yoonpay.server.lifecycle.StatusEvents.Cause;
import dev.yoonpay.server.phone.Phones;
import dev.yoonpay.server.provider.ProviderRegistry;
import dev.yoonpay.server.web.ApiProblem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Currency;
import java.util.Map;

/**
 * Sends money out. Exactly one provider is called, exactly once: a payout is never retried
 * and never failed over, whatever the outcome — a double payout does not come back. A submit
 * whose answer was lost becomes UNKNOWN and is resolved only by the provider's status API.
 *
 * <p>Ledger reservations happen in {@link PayoutTransitions}.
 */
@Service
public class PayoutService {

    private final PayoutRepository payouts;
    private final PayoutTransitions transitions;
    private final StatusEvents events;
    private final ProviderRegistry providers;
    private final Router router;
    private final TransactionTemplate tx;

    public PayoutService(PayoutRepository payouts, PayoutTransitions transitions, StatusEvents events,
                         ProviderRegistry providers, Router router, TransactionTemplate tx) {
        this.payouts = payouts;
        this.transitions = transitions;
        this.events = events;
        this.providers = providers;
        this.router = router;
        this.tx = tx;
    }

    public PayoutRecord create(AppPrincipal app, CreatePayoutRequest req) {
        Currency currency = currency(req.currency());
        String alias = req.recipient().piAlias() == null || req.recipient().piAlias().isBlank()
                ? null : req.recipient().piAlias().trim();
        if (alias == null && (req.recipient().phone() == null || req.recipient().phone().isBlank())) {
            throw ApiProblem.invalid("recipient.phone or recipient.pi_alias is required");
        }
        String phone = req.recipient().phone() == null || req.recipient().phone().isBlank()
                ? null : Phones.normalize(req.recipient().phone(), req.country());

        var decision = router.route(new RouteRequest(Operation.PAYOUT, req.country(), req.method(), currency,
                req.provider() == null ? null : providerId(req.provider())), providers.candidates(app));
        if (decision instanceof Router.Decision.NoRoute(String code, String message)) {
            throw ApiProblem.unprocessable(code, message);
        }
        var route = (Router.Decision.Route) decision;
        ProviderId providerId = route.providers().getFirst();
        PaymentProvider provider = providers.find(app, providerId.value()).orElseThrow();

        PayoutRecord payout = new PayoutRecord(Ids.payout(), app.id(), PayoutStatus.CREATED.name(), req.amount(),
                currency.getCurrencyCode(), req.country(), req.method(), req.reference(), phone, providerId.value(),
                null, route.reason() + "; payouts never fail over", false, null, null, null, null, alias);
        tx.executeWithoutResult(s -> {
            payouts.insert(payout);
            events.record(app.id(), "payout", payout.id(), null, PayoutStatus.CREATED, Decision.APPLY, Cause.api, null, null);
        });

        Money amount = new Money(req.amount(), currency);
        PayoutRequest call = new PayoutRequest(payout.id(), amount, req.country(), req.method(), phone, null, alias);
        CallOutcome outcome = providers.call(app, providerId.value(), Operation.PAYOUT, () -> provider.payout(call));

        switch (outcome) {
            case CallOutcome.Accepted a -> transitions.apply(app, payout.id(), PayoutStatus.PROCESSING, Cause.provider_call,
                    null, "accepted by provider", Map.of("provider_reference", a.reference().value()));
            case CallOutcome.Unknown u -> transitions.apply(app, payout.id(), PayoutStatus.UNKNOWN, Cause.provider_call,
                    null, "provider outcome unknown: " + u.cause() + "; never retried",
                    u.reference() == null ? Map.of() : Map.of("provider_reference", u.reference().value()));
            case CallOutcome.Rejected r -> transitions.apply(app, payout.id(), PayoutStatus.FAILED, Cause.provider_call,
                    null, "rejected by provider", Map.of("failure_code", r.code().toLowerCase(), "failure_message", r.message()));
        }
        return payouts.find(app.id(), payout.id()).orElseThrow();
    }

    private static Currency currency(String code) {
        try {
            return Currency.getInstance(code);
        } catch (IllegalArgumentException e) {
            throw ApiProblem.invalid("Unknown currency " + code);
        }
    }

    private static ProviderId providerId(String value) {
        try {
            return new ProviderId(value);
        } catch (IllegalArgumentException e) {
            throw ApiProblem.invalid("Invalid provider " + value);
        }
    }
}
