package dev.yoonpay.core.lifecycle;

/** Refund lifecycle. Same unknown-outcome rule as payouts. */
public enum RefundStatus {
    CREATED, PENDING, UNKNOWN, REFUNDED, FAILED;

    public Decision decide(RefundStatus target) {
        if (target == this) {
            return Decision.IGNORE;
        }
        return switch (this) {
            case CREATED -> switch (target) {
                case PENDING, UNKNOWN, FAILED -> Decision.APPLY;
                default -> throw new IllegalTransitionException(this, target);
            };
            case PENDING -> switch (target) {
                case UNKNOWN, REFUNDED, FAILED -> Decision.APPLY;
                default -> throw new IllegalTransitionException(this, target);
            };
            case UNKNOWN -> switch (target) {
                case REFUNDED, FAILED -> Decision.APPLY;
                case PENDING -> Decision.IGNORE;
                default -> throw new IllegalTransitionException(this, target);
            };
            case REFUNDED -> switch (target) {
                case FAILED, PENDING, UNKNOWN -> Decision.IGNORE;
                default -> throw new IllegalTransitionException(this, target);
            };
            case FAILED -> switch (target) {
                case REFUNDED -> Decision.APPLY;
                case PENDING, UNKNOWN -> Decision.IGNORE;
                default -> throw new IllegalTransitionException(this, target);
            };
        };
    }

    public boolean isFinal() {
        return this == REFUNDED || this == FAILED;
    }
}
