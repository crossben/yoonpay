package dev.yoonpay.testkit;

import java.time.Duration;

/** How {@link FakeProvider} answers the next mutating call (collect, refund or payout). */
public sealed interface Behaviour {

    /** Provider accepts; the operation stays pending until the test settles it. */
    record Accept() implements Behaviour {
    }

    /** Definite refusal from the provider. */
    record Reject(String code) implements Behaviour {
    }

    /** Provider is down before anything is sent (connection refused): a definite, retryable-elsewhere rejection. */
    record Down() implements Behaviour {
    }

    /** Blocks for {@code duration}, then answers Unknown — as the caller's timeout would. Nothing is created. */
    record Hang(Duration duration) implements Behaviour {
    }

    /**
     * The trap: the provider creates the operation, but the answer is lost (read timeout).
     * The caller sees Unknown; the status API later reveals it exists.
     */
    record TimeoutAfterAccept() implements Behaviour {
    }

    static Behaviour accept() {
        return new Accept();
    }

    static Behaviour reject(String code) {
        return new Reject(code);
    }

    static Behaviour down() {
        return new Down();
    }

    static Behaviour hang(Duration duration) {
        return new Hang(duration);
    }

    static Behaviour timeoutAfterAccept() {
        return new TimeoutAfterAccept();
    }
}
