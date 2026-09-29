package dev.yoonpay.core.lifecycle;

/**
 * Payout (disbursement) lifecycle. UNKNOWN is a submit whose outcome was not
 * seen (timeout / 5xx): only the provider's status API may resolve it, never a retry.
 * A late PAID after FAILED is applied — the money left, the books must say so.
 */
public enum PayoutStatus {
    CREATED, PROCESSING, UNKNOWN, PAID, FAILED;

    public Decision decide(PayoutStatus target) {
        if (target == this) {
            return Decision.IGNORE;
        }
        return switch (this) {
            case CREATED -> switch (target) {
                case PROCESSING, UNKNOWN, FAILED -> Decision.APPLY;
                default -> throw new IllegalTransitionException(this, target);
            };
            case PROCESSING -> switch (target) {
                case UNKNOWN, PAID, FAILED -> Decision.APPLY;
                default -> throw new IllegalTransitionException(this, target);
            };
            case UNKNOWN -> switch (target) {
                case PAID, FAILED -> Decision.APPLY;
                // The provider saying "still processing" does not undo our uncertainty.
                case PROCESSING -> Decision.IGNORE;
                default -> throw new IllegalTransitionException(this, target);
            };
            case PAID -> switch (target) {
                case FAILED, PROCESSING, UNKNOWN -> Decision.IGNORE;
                default -> throw new IllegalTransitionException(this, target);
            };
            case FAILED -> switch (target) {
                case PAID -> Decision.APPLY;
                case PROCESSING, UNKNOWN -> Decision.IGNORE;
                default -> throw new IllegalTransitionException(this, target);
            };
        };
    }

    public boolean isFinal() {
        return this == PAID || this == FAILED;
    }
}
