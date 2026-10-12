package dev.yoonpay.core.lifecycle;

/**
 * Subscription lifecycle (ADR-0026). A renewal of an active subscription is not a status change
 * (it moves the period end), so ACTIVE → ACTIVE is IGNORE here and the transitions class extends
 * the period. EXPIRED and CANCELED are final for requests and sweeps, but a confirmed payment of an
 * open invoice re-activates them: money received must not be lost.
 */
public enum SubscriptionStatus {
    INCOMPLETE, ACTIVE, PAST_DUE, EXPIRED, CANCELED,
    /** A free trial (ADR-0028): entitled, nothing paid yet. Only ever a starting status. */
    TRIALING;

    public Decision decide(SubscriptionStatus target) {
        if (target == this) {
            return Decision.IGNORE;
        }
        return switch (this) {
            case INCOMPLETE -> switch (target) {
                case ACTIVE, EXPIRED, CANCELED -> Decision.APPLY;
                default -> throw new IllegalTransitionException(this, target);
            };
            case ACTIVE -> switch (target) {
                // Period end: unpaid → past due (even with zero grace: the sweep expires it next).
                case PAST_DUE, CANCELED -> Decision.APPLY;
                default -> throw new IllegalTransitionException(this, target);
            };
            case PAST_DUE -> switch (target) {
                case ACTIVE, EXPIRED, CANCELED -> Decision.APPLY;
                default -> throw new IllegalTransitionException(this, target);
            };
            case EXPIRED, CANCELED -> switch (target) {
                // A late confirmed payment re-activates.
                case ACTIVE -> Decision.APPLY;
                // A stale sweep or a cancel request on an ended subscription: late news.
                case PAST_DUE, EXPIRED, CANCELED -> Decision.IGNORE;
                default -> throw new IllegalTransitionException(this, target);
            };
            case TRIALING -> switch (target) {
                // Paid during the trial; the trial ends unpaid (grace, like a renewal); canceled.
                case ACTIVE, PAST_DUE, CANCELED -> Decision.APPLY;
                default -> throw new IllegalTransitionException(this, target);
            };
        };
    }

    /** Whether the customer should have access (ADR-0026: grace is entitled; ADR-0028: so is a trial). */
    public boolean isEntitled() {
        return this == ACTIVE || this == PAST_DUE || this == TRIALING;
    }
}
