package dev.yoonpay.server.billing;

import dev.yoonpay.core.lifecycle.Decision;
import dev.yoonpay.core.lifecycle.InvoiceStatus;
import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.lifecycle.StatusEvents;
import dev.yoonpay.server.lifecycle.StatusEvents.Cause;
import dev.yoonpay.server.outbox.Outbox;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * The only way an invoice's status changes. In one transaction: lock, ask the state machine,
 * record the event, apply, write the outbound event (ADR-0027).
 */
@Component
public class InvoiceTransitions {

    private final InvoiceRepository invoices;
    private final StatusEvents events;
    private final Outbox outbox;
    private final BillingViews views;
    private final TransactionTemplate tx;

    public InvoiceTransitions(InvoiceRepository invoices, StatusEvents events, Outbox outbox, BillingViews views,
                              TransactionTemplate tx) {
        this.invoices = invoices;
        this.events = events;
        this.outbox = outbox;
        this.views = views;
        this.tx = tx;
    }

    public Decision apply(AppPrincipal app, String id, InvoiceStatus to, Cause cause, String detail,
                          Map<String, Object> fields) {
        return tx.execute(s -> {
            InvoiceRecord current = invoices.lock(app.id(), id).orElseThrow();
            InvoiceStatus from = InvoiceStatus.valueOf(current.status());
            Decision decision = from.decide(to);
            events.record(app.id(), "invoice", id, from, to, decision, cause, null, detail);
            if (decision != Decision.APPLY) {
                return decision;
            }
            Map<String, Object> f = new HashMap<>(fields);
            f.put("status", to.name());
            invoices.update(id, f);
            InvoiceRecord updated = invoices.find(app.id(), id).orElseThrow();
            switch (to) {
                case PAID -> outbox.emit(app, "invoice.paid", "invoice", id, views.invoice(updated));
                case VOID -> outbox.emit(app, "invoice.voided", "invoice", id, views.invoice(updated));
                default -> {
                }
            }
            return decision;
        });
    }

    /** Records a new invoice's creation in its history and announces it. */
    public void created(AppPrincipal app, InvoiceRecord invoice) {
        tx.executeWithoutResult(s -> {
            events.record(app.id(), "invoice", invoice.id(), null, InvoiceStatus.OPEN, Decision.APPLY, Cause.api, null, null);
            outbox.emit(app, "invoice.created", "invoice", invoice.id(), views.invoice(invoice));
        });
    }
}
