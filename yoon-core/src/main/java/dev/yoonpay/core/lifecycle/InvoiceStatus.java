package dev.yoonpay.core.lifecycle;

/**
 * Invoice lifecycle (ADR-0027). PAID ignores everything later. VOID and UNCOLLECTIBLE accept a
 * late confirmed payment: the money arrived, the record must say so.
 */
public enum InvoiceStatus {
    OPEN, PAID, VOID, UNCOLLECTIBLE;

    public Decision decide(InvoiceStatus target) {
        if (target == this) {
            return Decision.IGNORE;
        }
        return switch (this) {
            case OPEN -> Decision.APPLY;
            case PAID -> switch (target) {
                case VOID, UNCOLLECTIBLE -> Decision.IGNORE;
                default -> throw new IllegalTransitionException(this, target);
            };
            case VOID, UNCOLLECTIBLE -> switch (target) {
                case PAID -> Decision.APPLY;
                case VOID, UNCOLLECTIBLE -> Decision.IGNORE;
                default -> throw new IllegalTransitionException(this, target);
            };
        };
    }
}
