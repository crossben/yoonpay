package dev.yoonpay.server.payment;

import dev.yoonpay.core.ledger.Accounts;
import dev.yoonpay.core.ledger.LedgerPosting;
import dev.yoonpay.core.lifecycle.Decision;
import dev.yoonpay.core.lifecycle.PaymentStatus;
import dev.yoonpay.core.money.Money;
import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.ledger.LedgerRepository;
import dev.yoonpay.server.lifecycle.Alerts;
import dev.yoonpay.server.lifecycle.StatusEvents;
import dev.yoonpay.server.lifecycle.StatusEvents.Cause;
import dev.yoonpay.server.outbox.Outbox;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Currency;
import java.util.HashMap;
import java.util.Map;

/**
 * The only way a payment's status changes, whoever asks (API call, webhook, sweep, admin).
 * In one transaction: lock the row, ask the state machine, record the event, apply, post to
 * the ledger and write the outbound event.
 */
@Component
public class PaymentTransitions {

    private final PaymentRepository payments;
    private final StatusEvents events;
    private final LedgerRepository ledger;
    private final Outbox outbox;
    private final Alerts alerts;
    private final TransactionTemplate tx;
    private final dev.yoonpay.server.lifecycle.StatusMetrics metrics;

    public PaymentTransitions(PaymentRepository payments, StatusEvents events, LedgerRepository ledger,
                              Outbox outbox, Alerts alerts, TransactionTemplate tx,
                            dev.yoonpay.server.lifecycle.StatusMetrics metrics) {
        this.metrics = metrics;
        this.payments = payments;
        this.events = events;
        this.ledger = ledger;
        this.outbox = outbox;
        this.alerts = alerts;
        this.tx = tx;
    }

    /** @param fields extra columns to set when applied; empty-string values are stored as null */
    public Decision apply(AppPrincipal app, String id, PaymentStatus to, Cause cause, String rawStatus,
                          String detail, Map<String, Object> fields) {
        return tx.execute(s -> {
            PaymentRecord current = payments.lock(app.id(), id).orElseThrow();
            PaymentStatus from = PaymentStatus.valueOf(current.status());
            Decision decision = from.decide(to);
            events.record(app.id(), "payment", id, from, to, decision, cause, rawStatus, detail);
            if (decision != Decision.APPLY) {
                return decision;
            }

            Map<String, Object> clean = new HashMap<>(fields);
            clean.replaceAll((k, v) -> "".equals(v) ? null : v);
            payments.update(id, to.name(), clean);
            PaymentRecord updated = payments.find(app.id(), id).orElseThrow();
            metrics.statusChanged("payment", to.name(), updated.provider());

            switch (to) {
                case SUCCEEDED -> {
                    ledger.post(app.id(), LedgerPosting.transfer("payment " + id + " succeeded",
                            Accounts.providerBalance(updated.provider()), Accounts.customerFunds(updated.provider()),
                            new Money(updated.amount(), Currency.getInstance(updated.currency()))));
                    if (from == PaymentStatus.FAILED || from == PaymentStatus.EXPIRED) {
                        alerts.raise(Alerts.Type.late_success, id, "was " + from + ", provider confirms paid");
                    }
                    outbox.emit(app, "payment.succeeded", "payment", id, PaymentResponse.of(updated));
                }
                case FAILED -> outbox.emit(app, "payment.failed", "payment", id, PaymentResponse.of(updated));
                case EXPIRED -> outbox.emit(app, "payment.expired", "payment", id, PaymentResponse.of(updated));
                default -> {
                }
            }
            return decision;
        });
    }
}
