package dev.yoonpay.server.billing;

import dev.yoonpay.core.lifecycle.Decision;
import dev.yoonpay.core.lifecycle.SubscriptionStatus;
import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.lifecycle.StatusEvents;
import dev.yoonpay.server.lifecycle.StatusEvents.Cause;
import dev.yoonpay.server.outbox.Outbox;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * The only way a subscription changes (ADR-0026). In one transaction: lock the row, ask the state
 * machine, record the event, apply the fields, write the outbound event. A paid period that keeps
 * the status (renewal of an active subscription) is a change too: {@link #extend}.
 */
@Component
public class SubscriptionTransitions {

    private final SubscriptionRepository subscriptions;
    private final StatusEvents events;
    private final Outbox outbox;
    private final BillingViews views;
    private final TransactionTemplate tx;

    public SubscriptionTransitions(SubscriptionRepository subscriptions, StatusEvents events, Outbox outbox,
                                   BillingViews views, TransactionTemplate tx) {
        this.subscriptions = subscriptions;
        this.events = events;
        this.outbox = outbox;
        this.views = views;
        this.tx = tx;
    }

    /** A status change, with the columns that go with it. */
    public Decision apply(AppPrincipal app, String id, SubscriptionStatus to, Cause cause, String detail,
                          Map<String, Object> fields) {
        return tx.execute(s -> {
            SubscriptionRecord current = subscriptions.lock(app.id(), id).orElseThrow();
            SubscriptionStatus from = SubscriptionStatus.valueOf(current.status());
            Decision decision = from.decide(to);
            events.record(app.id(), "subscription", id, from, to, decision, cause, null, detail);
            if (decision != Decision.APPLY) {
                return decision;
            }
            Map<String, Object> f = new HashMap<>(fields);
            f.put("status", to.name());
            subscriptions.update(id, f);
            SubscriptionRecord updated = subscriptions.find(app.id(), id).orElseThrow();
            String type = switch (to) {
                case ACTIVE -> from == SubscriptionStatus.PAST_DUE ? "subscription.renewed" : "subscription.activated";
                case PAST_DUE -> "subscription.past_due";
                case EXPIRED -> "subscription.expired";
                case CANCELED -> "subscription.canceled";
                case INCOMPLETE, TRIALING -> null; // starting statuses: subscription.created says it
            };
            if (type != null) {
                outbox.emit(app, type, "subscription", id, views.subscription(updated));
            }
            return decision;
        });
    }

    /** Fields that change without a status change, announced as {@code eventType} (renewed, changed…). */
    public void update(AppPrincipal app, String id, Cause cause, String detail, Map<String, Object> fields, String eventType) {
        tx.executeWithoutResult(s -> {
            SubscriptionRecord current = subscriptions.lock(app.id(), id).orElseThrow();
            SubscriptionStatus status = SubscriptionStatus.valueOf(current.status());
            events.record(app.id(), "subscription", id, status, status, Decision.IGNORE, cause, null, detail);
            subscriptions.update(id, fields);
            if (eventType != null) {
                outbox.emit(app, eventType, "subscription", id, views.subscription(subscriptions.find(app.id(), id).orElseThrow()));
            }
        });
    }

    /** A new subscription: its first history line and {@code subscription.created}. */
    public void created(AppPrincipal app, String id) {
        tx.executeWithoutResult(s -> {
            events.record(app.id(), "subscription", id, null, SubscriptionStatus.INCOMPLETE, Decision.APPLY, Cause.api, null, null);
            outbox.emit(app, "subscription.created", "subscription", id,
                    views.subscription(subscriptions.find(app.id(), id).orElseThrow()));
        });
    }
}
