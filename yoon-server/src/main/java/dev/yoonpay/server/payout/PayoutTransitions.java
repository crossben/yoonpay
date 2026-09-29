package dev.yoonpay.server.payout;

import dev.yoonpay.core.ledger.Accounts;
import dev.yoonpay.core.ledger.LedgerPosting;
import dev.yoonpay.core.lifecycle.Decision;
import dev.yoonpay.core.lifecycle.PayoutStatus;
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
 * The only way a payout's status changes. Ledger: the amount is reserved once the provider may
 * have the payout (CREATED → PROCESSING/UNKNOWN), released on FAILED, paid out on PAID. A late
 * PAID after FAILED pays straight from the balance (the reservation was already released).
 */
@Component
public class PayoutTransitions {

    private final PayoutRepository payouts;
    private final StatusEvents events;
    private final LedgerRepository ledger;
    private final Outbox outbox;
    private final Alerts alerts;
    private final TransactionTemplate tx;

    public PayoutTransitions(PayoutRepository payouts, StatusEvents events, LedgerRepository ledger, Outbox outbox,
                             Alerts alerts, TransactionTemplate tx) {
        this.payouts = payouts;
        this.events = events;
        this.ledger = ledger;
        this.outbox = outbox;
        this.alerts = alerts;
        this.tx = tx;
    }

    public Decision apply(AppPrincipal app, String id, PayoutStatus to, Cause cause, String rawStatus,
                          String detail, Map<String, Object> fields) {
        return tx.execute(s -> {
            PayoutRecord current = payouts.lock(app.id(), id).orElseThrow();
            PayoutStatus from = PayoutStatus.valueOf(current.status());
            Decision decision = from.decide(to);
            events.record(app.id(), "payout", id, from, to, decision, cause, rawStatus, detail);
            if (decision != Decision.APPLY) {
                return decision;
            }
            payouts.update(id, to.name(), new HashMap<>(fields));
            PayoutRecord updated = payouts.find(app.id(), id).orElseThrow();
            String provider = updated.provider();
            Money amount = new Money(updated.amount(), Currency.getInstance(updated.currency()));
            boolean wasReserved = from == PayoutStatus.PROCESSING || from == PayoutStatus.UNKNOWN;

            switch (to) {
                case PROCESSING, UNKNOWN -> {
                    if (from == PayoutStatus.CREATED) {
                        ledger.post(app.id(), LedgerPosting.transfer("payout " + id + " reserved",
                                Accounts.payoutReserved(provider), Accounts.providerBalance(provider), amount));
                    }
                }
                case PAID -> {
                    String source = wasReserved ? Accounts.payoutReserved(provider) : Accounts.providerBalance(provider);
                    ledger.post(app.id(), LedgerPosting.transfer("payout " + id + " paid",
                            Accounts.customerFunds(provider), source, amount));
                    if (from == PayoutStatus.FAILED) {
                        alerts.raise(Alerts.Type.late_payout, id, "was FAILED, provider confirms paid");
                    }
                    outbox.emit(app, "payout.paid", "payout", id, PayoutResponse.of(updated));
                }
                case FAILED -> {
                    if (wasReserved) {
                        ledger.post(app.id(), LedgerPosting.transfer("payout " + id + " failed, reservation released",
                                Accounts.providerBalance(provider), Accounts.payoutReserved(provider), amount));
                    }
                    outbox.emit(app, "payout.failed", "payout", id, PayoutResponse.of(updated));
                }
                default -> {
                }
            }
            return decision;
        });
    }

    /** Flags a payout whose outcome stayed unknown too long, once, with an event and an alert. */
    public void flagForReview(AppPrincipal app, String id, String detail) {
        tx.executeWithoutResult(s -> {
            PayoutRecord current = payouts.lock(app.id(), id).orElseThrow();
            if (current.needsReview()) {
                return;
            }
            payouts.update(id, current.status(), Map.of("needs_review", true));
            alerts.raise(Alerts.Type.payout_needs_review, id, detail);
            outbox.emit(app, "payout.needs_review", "payout", id,
                    PayoutResponse.of(payouts.find(app.id(), id).orElseThrow()));
        });
    }
}
