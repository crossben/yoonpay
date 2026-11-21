package dev.yoonpay.server.refund;

import dev.yoonpay.core.lifecycle.Decision;
import dev.yoonpay.core.lifecycle.IllegalTransitionException;
import dev.yoonpay.core.lifecycle.RefundStatus;
import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.auth.Applications;
import dev.yoonpay.server.lifecycle.StatusEvents;
import dev.yoonpay.server.lifecycle.StatusEvents.Cause;
import dev.yoonpay.server.web.ApiProblem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * What an operator does to a refund after checking with the provider (money-safety plan
 * 2026-11): settle one Yoon cannot check by itself, or release the amount of one that failed after
 * the provider saw it. Used by the admin API and the {@code refunds} CLI command.
 */
@Service
public class RefundOperations {

    private final RefundRepository refunds;
    private final RefundTransitions transitions;
    private final StatusEvents events;
    private final Applications applications;
    private final TransactionTemplate tx;

    public RefundOperations(RefundRepository refunds, RefundTransitions transitions, StatusEvents events,
                            Applications applications, TransactionTemplate tx) {
        this.refunds = refunds;
        this.transitions = transitions;
        this.events = events;
        this.applications = applications;
        this.tx = tx;
    }

    /** Settles a refund as refunded or failed, through the state machine, ledger and events. */
    public RefundRecord resolve(String id, RefundStatus target, String note) {
        if (target != RefundStatus.REFUNDED && target != RefundStatus.FAILED) {
            throw new IllegalArgumentException("a refund is resolved as refunded or failed");
        }
        RefundRecord refund = refunds.findAnyApplication(id).orElseThrow(() -> ApiProblem.notFound("Refund", id));
        AppPrincipal app = applications.find(refund.applicationId()).orElseThrow();
        Map<String, Object> fields = new HashMap<>(Map.of("needs_review", false));
        if (target == RefundStatus.FAILED) {
            fields.put("failure_code", "resolved_failed");
            fields.put("failure_message", note);
        }
        Decision d;
        try {
            d = transitions.apply(app, id, target, Cause.admin, null, "resolved by operator: " + note, fields);
        } catch (IllegalTransitionException e) {
            throw ApiProblem.unprocessable("illegal_transition", e.getMessage());
        }
        if (d == Decision.IGNORE) {
            throw ApiProblem.unprocessable("already_final", "Refund " + id + " is already " + refund.status().toLowerCase());
        }
        return refunds.find(app.id(), id).orElseThrow();
    }

    /**
     * Frees the amount of a refund that failed after the provider saw it, so it can be refunded
     * again. Only once the operator has confirmed with the provider that no money left: a late
     * success after this still applies and raises {@code refund_exceeds_payment}.
     */
    public RefundRecord release(String id, String note) {
        RefundRecord refund = refunds.findAnyApplication(id).orElseThrow(() -> ApiProblem.notFound("Refund", id));
        AppPrincipal app = applications.find(refund.applicationId()).orElseThrow();
        tx.executeWithoutResult(s -> {
            RefundRecord current = refunds.lock(app.id(), id).orElseThrow();
            if (!current.status().equals(RefundStatus.FAILED.name()) || current.providerReference() == null
                    || current.amountReleasedAt() != null) {
                throw ApiProblem.unprocessable("nothing_to_release",
                        "Only a failed refund the provider saw holds its amount; refund " + id + " holds nothing");
            }
            refunds.releaseAmount(id);
            events.record(app.id(), "refund", id, RefundStatus.FAILED, RefundStatus.FAILED, Decision.IGNORE,
                    Cause.admin, null, "amount released by operator: " + note);
        });
        return refunds.find(app.id(), id).orElseThrow();
    }
}
