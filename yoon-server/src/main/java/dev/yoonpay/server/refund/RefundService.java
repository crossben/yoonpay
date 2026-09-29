package dev.yoonpay.server.refund;

import dev.yoonpay.core.id.Ids;
import dev.yoonpay.core.lifecycle.Decision;
import dev.yoonpay.core.lifecycle.PaymentStatus;
import dev.yoonpay.core.lifecycle.RefundStatus;
import dev.yoonpay.core.money.Money;
import dev.yoonpay.core.provider.CallOutcome;
import dev.yoonpay.core.provider.Operation;
import dev.yoonpay.core.provider.PaymentProvider;
import dev.yoonpay.core.provider.ProviderReference;
import dev.yoonpay.core.provider.RefundRequest;
import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.lifecycle.StatusEvents;
import dev.yoonpay.server.lifecycle.StatusEvents.Cause;
import dev.yoonpay.server.payment.PaymentRecord;
import dev.yoonpay.server.payment.PaymentRepository;
import dev.yoonpay.server.provider.ProviderRegistry;
import dev.yoonpay.server.web.ApiProblem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Currency;
import java.util.HashMap;
import java.util.Map;

/**
 * Refunds a succeeded payment, in full or in part, on the provider that collected it. The
 * payment row is locked while the refund is reserved, so concurrent refunds can never add up
 * to more than was paid. Refunds are never retried and never sent to another provider.
 */
@Service
public class RefundService {

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final StatusEvents events;
    private final ProviderRegistry providers;
    private final TransactionTemplate tx;

    public RefundService(PaymentRepository payments, RefundRepository refunds, StatusEvents events,
                         ProviderRegistry providers, TransactionTemplate tx) {
        this.payments = payments;
        this.refunds = refunds;
        this.events = events;
        this.providers = providers;
        this.tx = tx;
    }

    public RefundRecord create(AppPrincipal app, String paymentId, CreateRefundRequest req) {
        record Reserved(RefundRecord refund, PaymentRecord payment, PaymentProvider provider) {
        }

        Reserved reserved = tx.execute(s -> {
            PaymentRecord payment = payments.lock(app.id(), paymentId)
                    .orElseThrow(() -> ApiProblem.notFound("Payment", paymentId));
            if (!PaymentStatus.valueOf(payment.status()).equals(PaymentStatus.SUCCEEDED)) {
                throw ApiProblem.unprocessable("payment_not_refundable",
                        "Only succeeded payments can be refunded; this one is " + payment.status().toLowerCase());
            }
            PaymentProvider provider = providers.find(app, payment.provider())
                    .orElseThrow(() -> ApiProblem.unprocessable("provider_not_configured",
                            "Provider " + payment.provider() + " is no longer configured for this application"));
            if (!provider.capabilities().supports(Operation.REFUND, payment.country(), payment.method(),
                    Currency.getInstance(payment.currency()))) {
                throw ApiProblem.unprocessable("refund_not_supported",
                        payment.provider() + " cannot refund " + payment.method() + " payments; send the money back as a payout");
            }

            long remaining = payment.amount() - refunds.committedAmount(paymentId);
            long amount = req.amount() == null ? remaining : req.amount();
            if (amount <= 0 || amount > remaining) {
                throw ApiProblem.unprocessable("refund_exceeds_payment",
                        "Refund of " + amount + " exceeds the refundable " + remaining + " " + payment.currency());
            }

            RefundRecord refund = new RefundRecord(Ids.refund(), app.id(), paymentId, RefundStatus.CREATED.name(),
                    amount, payment.currency(), req.reason(), payment.provider(), null, null, null, null, null);
            refunds.insert(refund);
            events.record(app.id(), "refund", refund.id(), null, RefundStatus.CREATED, Decision.APPLY, Cause.api, null, null);
            return new Reserved(refund, payment, provider);
        });

        RefundRecord refund = reserved.refund();
        RefundRequest call = new RefundRequest(refund.id(), new ProviderReference(reserved.payment().providerReference()),
                new Money(refund.amount(), Currency.getInstance(refund.currency())), refund.reason());
        CallOutcome outcome = providers.call(app, refund.provider(), () -> reserved.provider().refund(call));

        switch (outcome) {
            case CallOutcome.Accepted a -> move(app, refund.id(), RefundStatus.PENDING, "accepted by provider",
                    Map.of("provider_reference", a.reference().value()));
            case CallOutcome.Unknown u -> move(app, refund.id(), RefundStatus.UNKNOWN,
                    "provider outcome unknown: " + u.cause(), Map.of());
            case CallOutcome.Rejected r -> move(app, refund.id(), RefundStatus.FAILED, "rejected by provider",
                    Map.of("failure_code", r.code().toLowerCase(), "failure_message", r.message()));
        }
        return refunds.find(app.id(), refund.id()).orElseThrow();
    }

    private void move(AppPrincipal app, String id, RefundStatus to, String detail, Map<String, Object> fields) {
        tx.executeWithoutResult(s -> {
            RefundRecord current = refunds.lock(app.id(), id).orElseThrow();
            RefundStatus from = RefundStatus.valueOf(current.status());
            Decision decision = from.decide(to);
            events.record(app.id(), "refund", id, from, to, decision, Cause.provider_call, null, detail);
            if (decision == Decision.APPLY) {
                refunds.update(id, to.name(), new HashMap<>(fields));
            }
        });
    }
}
