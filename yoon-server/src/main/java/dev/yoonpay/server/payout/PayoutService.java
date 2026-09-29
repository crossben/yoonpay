package dev.yoonpay.server.payout;

import dev.yoonpay.core.id.Ids;
import dev.yoonpay.core.ledger.Accounts;
import dev.yoonpay.core.ledger.LedgerPosting;
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
import dev.yoonpay.server.ledger.LedgerRepository;
import dev.yoonpay.server.lifecycle.StatusEvents;
import dev.yoonpay.server.lifecycle.StatusEvents.Cause;
import dev.yoonpay.server.phone.Phones;
import dev.yoonpay.server.provider.ProviderRegistry;
import dev.yoonpay.server.web.ApiProblem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Currency;
import java.util.HashMap;
import java.util.Map;

/**
 * Sends money out. Exactly one provider is called, exactly once: a payout is never retried
 * and never failed over, whatever the outcome — a double payout does not come back. A submit
 * whose answer was lost becomes UNKNOWN and is resolved only by the provider's status API.
 *
 * <p>Once the provider may have the payout (accepted or unknown), the amount is reserved in
 * the shadow ledger in the same transaction as the status change.
 */
@Service
public class PayoutService {

    private final PayoutRepository payouts;
    private final StatusEvents events;
    private final ProviderRegistry providers;
    private final Router router;
    private final LedgerRepository ledger;
    private final TransactionTemplate tx;

    public PayoutService(PayoutRepository payouts, StatusEvents events, ProviderRegistry providers, Router router,
                         LedgerRepository ledger, TransactionTemplate tx) {
        this.payouts = payouts;
        this.events = events;
        this.providers = providers;
        this.router = router;
        this.ledger = ledger;
        this.tx = tx;
    }

    public PayoutRecord create(AppPrincipal app, CreatePayoutRequest req) {
        Currency currency = currency(req.currency());
        String phone = Phones.normalize(req.recipient().phone(), req.country());

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
                null, route.reason() + "; payouts never fail over", false, null, null, null, null);
        tx.executeWithoutResult(s -> {
            payouts.insert(payout);
            events.record(app.id(), "payout", payout.id(), null, PayoutStatus.CREATED, Decision.APPLY, Cause.api, null, null);
        });

        Money amount = new Money(req.amount(), currency);
        PayoutRequest call = new PayoutRequest(payout.id(), amount, req.country(), req.method(), phone, null);
        CallOutcome outcome = providers.call(app, providerId.value(), () -> provider.payout(call));

        switch (outcome) {
            case CallOutcome.Accepted a -> move(app, payout, PayoutStatus.PROCESSING, "accepted by provider",
                    Map.of("provider_reference", a.reference().value()), true);
            case CallOutcome.Unknown u -> move(app, payout, PayoutStatus.UNKNOWN,
                    "provider outcome unknown: " + u.cause() + "; never retried", Map.of(), true);
            case CallOutcome.Rejected r -> move(app, payout, PayoutStatus.FAILED, "rejected by provider",
                    Map.of("failure_code", r.code().toLowerCase(), "failure_message", r.message()), false);
        }
        return payouts.find(app.id(), payout.id()).orElseThrow();
    }

    private void move(AppPrincipal app, PayoutRecord payout, PayoutStatus to, String detail,
                      Map<String, Object> fields, boolean reserve) {
        tx.executeWithoutResult(s -> {
            PayoutRecord current = payouts.lock(app.id(), payout.id()).orElseThrow();
            PayoutStatus from = PayoutStatus.valueOf(current.status());
            Decision decision = from.decide(to);
            events.record(app.id(), "payout", payout.id(), from, to, decision, Cause.provider_call, null, detail);
            if (decision == Decision.APPLY) {
                payouts.update(payout.id(), to.name(), new HashMap<>(fields));
                if (reserve) {
                    ledger.post(app.id(), LedgerPosting.transfer("payout " + payout.id() + " reserved",
                            Accounts.payoutReserved(payout.provider()), Accounts.providerBalance(payout.provider()),
                            new Money(payout.amount(), Currency.getInstance(payout.currency()))));
                }
            }
        });
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
