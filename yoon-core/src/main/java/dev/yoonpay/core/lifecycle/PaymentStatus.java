package dev.yoonpay.core.lifecycle;

/**
 * Collection (pay-in) lifecycle.
 *
 * <p>SUCCEEDED is terminal: late failures are ignored. FAILED and EXPIRED yield to a
 * confirmed success, because losing a real payment is worse than surprising the app.
 */
public enum PaymentStatus {
    CREATED, PENDING, SUCCEEDED, FAILED, EXPIRED;

    public Decision decide(PaymentStatus target) {
        if (target == this) {
            return Decision.IGNORE;
        }
        return switch (this) {
            case CREATED -> switch (target) {
                // PENDING also covers "sent, outcome unknown" (timeout / 5xx).
                case PENDING, FAILED -> Decision.APPLY;
                default -> throw new IllegalTransitionException(this, target);
            };
            case PENDING -> switch (target) {
                case SUCCEEDED, FAILED, EXPIRED -> Decision.APPLY;
                default -> throw new IllegalTransitionException(this, target);
            };
            case SUCCEEDED -> switch (target) {
                case FAILED, EXPIRED -> Decision.IGNORE;
                default -> throw new IllegalTransitionException(this, target);
            };
            case FAILED, EXPIRED -> switch (target) {
                case SUCCEEDED -> Decision.APPLY;
                case FAILED, EXPIRED -> Decision.IGNORE;
                default -> throw new IllegalTransitionException(this, target);
            };
        };
    }

    public boolean isFinal() {
        return this == SUCCEEDED || this == FAILED || this == EXPIRED;
    }
}
