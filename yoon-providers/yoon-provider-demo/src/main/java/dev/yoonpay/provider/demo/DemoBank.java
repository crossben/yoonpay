package dev.yoonpay.provider.demo;

import dev.yoonpay.core.money.Money;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The demo provider's in-memory "bank": what was created and what the customer did. Shared by
 * the provider and the demo checkout page. Lost on restart — it is a demo.
 */
public final class DemoBank {

    public enum State { PENDING, PAID, DECLINED }

    /** @param callbackUrl where the demo provider sends its callback (Yoon's hook URL) */
    public record Checkout(String reference, Money amount, String description, URI returnUrl, URI callbackUrl,
                           State state) {
        Checkout with(State s) {
            return new Checkout(reference, amount, description, returnUrl, callbackUrl, s);
        }
    }

    private final Map<String, Checkout> checkouts = new ConcurrentHashMap<>();

    void open(Checkout c) {
        checkouts.putIfAbsent(c.reference(), c);
    }

    public Optional<Checkout> find(String reference) {
        return Optional.ofNullable(checkouts.get(reference));
    }

    /** The customer's decision on the demo checkout page. Only a pending checkout can change. */
    public Optional<Checkout> decide(String reference, boolean pay) {
        return Optional.ofNullable(checkouts.computeIfPresent(reference,
                (k, c) -> c.state() == State.PENDING ? c.with(pay ? State.PAID : State.DECLINED) : c));
    }
}
