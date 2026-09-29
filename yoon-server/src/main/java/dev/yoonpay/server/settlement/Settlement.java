package dev.yoonpay.server.settlement;

import dev.yoonpay.core.lifecycle.Decision;
import dev.yoonpay.core.lifecycle.PaymentStatus;
import dev.yoonpay.core.lifecycle.PayoutStatus;
import dev.yoonpay.core.lifecycle.RefundStatus;
import dev.yoonpay.core.money.Money;
import dev.yoonpay.core.provider.Operation;
import dev.yoonpay.core.provider.PaymentProvider;
import dev.yoonpay.core.provider.ProviderReference;
import dev.yoonpay.core.provider.StatusResult;
import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.lifecycle.Alerts;
import dev.yoonpay.server.lifecycle.StatusEvents;
import dev.yoonpay.server.lifecycle.StatusEvents.Cause;
import dev.yoonpay.server.payment.PaymentRecord;
import dev.yoonpay.server.payment.PaymentRepository;
import dev.yoonpay.server.payment.PaymentTransitions;
import dev.yoonpay.server.payout.PayoutRecord;
import dev.yoonpay.server.payout.PayoutRepository;
import dev.yoonpay.server.payout.PayoutTransitions;
import dev.yoonpay.server.provider.ProviderRegistry;
import dev.yoonpay.server.refund.RefundRecord;
import dev.yoonpay.server.refund.RefundRepository;
import dev.yoonpay.server.refund.RefundTransitions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Currency;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Asks the provider for the truth and applies it. One settle function per resource, two
 * callers: the webhook worker (primary) and the reconciliation sweep (fallback). A webhook is
 * only ever a hint that it is worth asking.
 *
 * <p>The status API is queried with the reference Yoon stored, never one taken from a
 * callback. A success whose confirmed amount differs from the requested amount is not applied.
 */
@Component
public class Settlement {

    public enum Outcome {
        /** A status change was applied. */
        APPLIED,
        /** The provider's answer changed nothing (duplicate, or late news the state machine ignores). */
        IGNORED,
        /** The provider says it is still in progress. */
        STILL_OPEN,
        /** No answer: provider down, no reference yet, or an unmapped status. Counted; retried later. */
        NO_ANSWER,
        /** The provider confirmed a different amount: not applied, alert raised. */
        AMOUNT_MISMATCH
    }

    private static final Logger log = LoggerFactory.getLogger(Settlement.class);

    private final ProviderRegistry providers;
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final PayoutRepository payouts;
    private final PaymentTransitions paymentTransitions;
    private final RefundTransitions refundTransitions;
    private final PayoutTransitions payoutTransitions;
    private final StatusEvents events;
    private final Alerts alerts;

    public Settlement(ProviderRegistry providers, PaymentRepository payments, RefundRepository refunds,
                      PayoutRepository payouts, PaymentTransitions paymentTransitions,
                      RefundTransitions refundTransitions, PayoutTransitions payoutTransitions,
                      StatusEvents events, Alerts alerts) {
        this.providers = providers;
        this.payments = payments;
        this.refunds = refunds;
        this.payouts = payouts;
        this.paymentTransitions = paymentTransitions;
        this.refundTransitions = refundTransitions;
        this.payoutTransitions = payoutTransitions;
        this.events = events;
        this.alerts = alerts;
    }

    public Outcome settlePayment(AppPrincipal app, PaymentRecord p, Cause cause) {
        Optional<PaymentProvider> provider = p.provider() == null ? Optional.empty() : providers.find(app, p.provider());
        if (provider.isEmpty()) {
            return Outcome.NO_ANSWER;
        }
        String ref = p.providerReference();
        if (ref == null) {
            for (String attempt : payments.unresolvedAttempts(p.id())) {
                Optional<ProviderReference> found = ask(() -> provider.get().lookup(Operation.COLLECT, attempt));
                if (found.isPresent()) {
                    ref = found.get().value();
                    payments.setProviderReference(p.id(), ref);
                    break;
                }
            }
        }
        if (ref == null) {
            payments.countStatusCheck(p.id());
            return Outcome.NO_ANSWER;
        }

        String reference = ref;
        StatusResult<PaymentStatus> result = ask(() -> provider.get().status(new ProviderReference(reference)));
        if (result == null || result.status() == null) {
            payments.countStatusCheck(p.id());
            if (result != null && result.rawStatus() != null) {
                alerts.raise(Alerts.Type.unmapped_provider_status, p.id(), "raw status '" + result.rawStatus() + "'");
            }
            return Outcome.NO_ANSWER;
        }

        Money expected = new Money(p.amount(), Currency.getInstance(p.currency()));
        if (result.confirmedAmount() != null && !result.confirmedAmount().equals(expected)) {
            alerts.raise(Alerts.Type.amount_mismatch, p.id(),
                    "provider confirms " + result.confirmedAmount() + ", expected " + expected);
            events.record(app.id(), "payment", p.id(), PaymentStatus.valueOf(p.status()), result.status(),
                    Decision.IGNORE, cause, result.rawStatus(),
                    "amount mismatch: provider confirms " + result.confirmedAmount() + ", expected " + expected);
            return Outcome.AMOUNT_MISMATCH;
        }

        if (!result.status().isFinal()) {
            return Outcome.STILL_OPEN;
        }
        Decision d = paymentTransitions.apply(app, p.id(), result.status(), cause, result.rawStatus(),
                "confirmed by provider status API", failureFields(result.status() == PaymentStatus.FAILED, result.rawStatus()));
        return d == Decision.APPLY ? Outcome.APPLIED : Outcome.IGNORED;
    }

    public Outcome settleRefund(AppPrincipal app, RefundRecord r, Cause cause) {
        Optional<PaymentProvider> provider = providers.find(app, r.provider());
        if (provider.isEmpty()) {
            return Outcome.NO_ANSWER;
        }
        String ref = r.providerReference();
        if (ref == null) {
            Optional<ProviderReference> found = ask(() -> provider.get().lookup(Operation.REFUND, r.id()));
            if (found.isPresent()) {
                ref = found.get().value();
                refunds.setProviderReference(r.id(), ref);
            }
        }
        if (ref == null) {
            refunds.countStatusCheck(r.id());
            return Outcome.NO_ANSWER;
        }
        String reference = ref;
        StatusResult<RefundStatus> result = ask(() -> provider.get().refundStatus(new ProviderReference(reference)));
        if (result == null || result.status() == null) {
            refunds.countStatusCheck(r.id());
            return Outcome.NO_ANSWER;
        }
        if (!result.status().isFinal()) {
            return Outcome.STILL_OPEN;
        }
        Decision d = refundTransitions.apply(app, r.id(), result.status(), cause, result.rawStatus(),
                "confirmed by provider status API", failureFields(result.status() == RefundStatus.FAILED, result.rawStatus()));
        return d == Decision.APPLY ? Outcome.APPLIED : Outcome.IGNORED;
    }

    public Outcome settlePayout(AppPrincipal app, PayoutRecord po, Cause cause) {
        Optional<PaymentProvider> provider = providers.find(app, po.provider());
        if (provider.isEmpty()) {
            return Outcome.NO_ANSWER;
        }
        String ref = po.providerReference();
        if (ref == null) {
            Optional<ProviderReference> found = ask(() -> provider.get().lookup(Operation.PAYOUT, po.id()));
            if (found.isPresent()) {
                ref = found.get().value();
                payouts.setProviderReference(po.id(), ref);
            }
        }
        if (ref == null) {
            payouts.countStatusCheck(po.id());
            return Outcome.NO_ANSWER;
        }
        String reference = ref;
        StatusResult<PayoutStatus> result = ask(() -> provider.get().payoutStatus(new ProviderReference(reference)));
        if (result == null || result.status() == null) {
            payouts.countStatusCheck(po.id());
            return Outcome.NO_ANSWER;
        }
        if (!result.status().isFinal()) {
            return Outcome.STILL_OPEN;
        }
        Decision d = payoutTransitions.apply(app, po.id(), result.status(), cause, result.rawStatus(),
                "confirmed by provider status API", failureFields(result.status() == PayoutStatus.FAILED, result.rawStatus()));
        return d == Decision.APPLY ? Outcome.APPLIED : Outcome.IGNORED;
    }

    private static Map<String, Object> failureFields(boolean failed, String raw) {
        return failed ? Map.of("failure_code", "provider_" + (raw == null ? "failed" : raw.toLowerCase()),
                "failure_message", "Provider reported " + (raw == null ? "failure" : raw)) : Map.of();
    }

    /** A provider query that throws is "no answer", never a failure. */
    private static <T> T ask(Supplier<T> query) {
        try {
            return query.get();
        } catch (RuntimeException e) {
            log.warn("Provider status query failed: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    private static <T> Optional<T> ask(OptionalSupplier<T> query) {
        try {
            return query.get();
        } catch (RuntimeException e) {
            log.warn("Provider lookup failed: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @FunctionalInterface
    private interface OptionalSupplier<T> {
        Optional<T> get();
    }
}
