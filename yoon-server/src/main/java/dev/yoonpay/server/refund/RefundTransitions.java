package dev.yoonpay.server.refund;

import dev.yoonpay.core.ledger.Accounts;
import dev.yoonpay.core.ledger.LedgerPosting;
import dev.yoonpay.core.lifecycle.Decision;
import dev.yoonpay.core.lifecycle.RefundStatus;
import dev.yoonpay.core.money.Money;
import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.ledger.LedgerRepository;
import dev.yoonpay.server.lifecycle.StatusEvents;
import dev.yoonpay.server.lifecycle.StatusEvents.Cause;
import dev.yoonpay.server.outbox.Outbox;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Currency;
import java.util.HashMap;
import java.util.Map;

/** The only way a refund's status changes. See {@code PaymentTransitions}. */
@Component
public class RefundTransitions {

    private final RefundRepository refunds;
    private final StatusEvents events;
    private final LedgerRepository ledger;
    private final Outbox outbox;
    private final TransactionTemplate tx;
    private final dev.yoonpay.server.lifecycle.StatusMetrics metrics;

    public RefundTransitions(RefundRepository refunds, StatusEvents events, LedgerRepository ledger, Outbox outbox,
                             TransactionTemplate tx,
                            dev.yoonpay.server.lifecycle.StatusMetrics metrics) {
        this.metrics = metrics;
        this.refunds = refunds;
        this.events = events;
        this.ledger = ledger;
        this.outbox = outbox;
        this.tx = tx;
    }

    public Decision apply(AppPrincipal app, String id, RefundStatus to, Cause cause, String rawStatus,
                          String detail, Map<String, Object> fields) {
        return tx.execute(s -> {
            RefundRecord current = refunds.lock(app.id(), id).orElseThrow();
            RefundStatus from = RefundStatus.valueOf(current.status());
            Decision decision = from.decide(to);
            events.record(app.id(), "refund", id, from, to, decision, cause, rawStatus, detail);
            if (decision != Decision.APPLY) {
                return decision;
            }
            refunds.update(id, to.name(), new HashMap<>(fields));
            RefundRecord updated = refunds.find(app.id(), id).orElseThrow();
            metrics.statusChanged("refund", to.name(), updated.provider());

            switch (to) {
                case REFUNDED -> {
                    ledger.post(app.id(), LedgerPosting.transfer("refund " + id + " of " + updated.paymentId(),
                            Accounts.customerFunds(updated.provider()), Accounts.providerBalance(updated.provider()),
                            new Money(updated.amount(), Currency.getInstance(updated.currency()))));
                    outbox.emit(app, "refund.succeeded", "refund", id, RefundResponse.of(updated));
                }
                case FAILED -> outbox.emit(app, "refund.failed", "refund", id, RefundResponse.of(updated));
                default -> {
                }
            }
            return decision;
        });
    }
}
