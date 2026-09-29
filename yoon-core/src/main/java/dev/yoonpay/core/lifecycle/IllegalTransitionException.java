package dev.yoonpay.core.lifecycle;

/** A status change the state machine forbids. Never swallowed: it signals a bug or a bad provider mapping. */
public class IllegalTransitionException extends RuntimeException {

    public IllegalTransitionException(Enum<?> from, Enum<?> to) {
        super("Illegal transition " + from + " -> " + to);
    }
}
