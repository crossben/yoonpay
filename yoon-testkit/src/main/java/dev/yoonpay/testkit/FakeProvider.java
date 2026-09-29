package dev.yoonpay.testkit;

import dev.yoonpay.core.lifecycle.PaymentStatus;
import dev.yoonpay.core.lifecycle.PayoutStatus;
import dev.yoonpay.core.lifecycle.RefundStatus;
import dev.yoonpay.core.money.Money;
import dev.yoonpay.core.provider.CallOutcome;
import dev.yoonpay.core.provider.Capabilities;
import dev.yoonpay.core.provider.Capability;
import dev.yoonpay.core.provider.CollectRequest;
import dev.yoonpay.core.provider.InboundWebhook;
import dev.yoonpay.core.provider.Operation;
import dev.yoonpay.core.provider.PaymentProvider;
import dev.yoonpay.core.provider.PayoutRequest;
import dev.yoonpay.core.provider.ProviderId;
import dev.yoonpay.core.provider.ProviderReference;
import dev.yoonpay.core.provider.RefundRequest;
import dev.yoonpay.core.provider.StatusResult;
import dev.yoonpay.core.provider.WebhookVerification;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A scriptable provider for tests. It can succeed, fail, hang, time out after accepting,
 * go down, report a partial amount, and emit duplicate, late or forged webhooks — every
 * way a real provider misbehaves that Yoon must survive.
 *
 * <p>Mutating calls consume scripted {@link Behaviour}s in order and fall back to
 * {@link Behaviour.Accept}. The test then decides the provider-side truth with
 * {@link #settle}, {@link #settlePayout} and {@link #settleRefund}, and drains the
 * callbacks it "sent" with {@link #drainWebhooks()}.
 */
public final class FakeProvider implements PaymentProvider {

    public static final String SIGNATURE_HEADER = "X-Fake-Signature";
    private static final Pattern REFERENCE = Pattern.compile("\"reference\":\"([^\"]+)\"");
    private static final Currency XOF = Currency.getInstance("XOF");

    private final ProviderId id;
    private final byte[] webhookSecret;
    private final Capabilities capabilities;
    private final Queue<Behaviour> script = new ConcurrentLinkedQueue<>();
    private final Queue<InboundWebhook> webhooks = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean down = new AtomicBoolean();
    private final AtomicInteger mutatingCalls = new AtomicInteger();

    private final Map<String, Record<PaymentStatus>> payments = new ConcurrentHashMap<>();
    private final Map<String, Record<RefundStatus>> refunds = new ConcurrentHashMap<>();
    private final Map<String, Record<PayoutStatus>> payouts = new ConcurrentHashMap<>();

    private record Record<S extends Enum<S>>(S status, String rawStatus, Money requested, Money paid) {
    }

    public FakeProvider(String id, String webhookSecret) {
        this(id, webhookSecret, defaultCapabilities());
    }

    public FakeProvider(String id, String webhookSecret, Capabilities capabilities) {
        this.id = new ProviderId(id);
        this.webhookSecret = webhookSecret.getBytes(StandardCharsets.UTF_8);
        this.capabilities = capabilities;
    }

    /** Collect, refund and payout in Senegal by Wave and Orange Money, in XOF. */
    public static Capabilities defaultCapabilities() {
        List<Capability> all = new ArrayList<>();
        for (Operation op : Operation.values()) {
            for (String method : List.of("wave", "orange_money")) {
                all.add(new Capability(op, "SN", method, XOF));
            }
        }
        return new Capabilities(Set.copyOf(all));
    }

    // ------------------------------------------------------------------ scripting

    /** Queue behaviours for the next mutating calls, in order. */
    public FakeProvider script(Behaviour... behaviours) {
        script.addAll(List.of(behaviours));
        return this;
    }

    /** While down, every call — including status queries — fails before reaching the provider. */
    public void setDown(boolean isDown) {
        down.set(isDown);
    }

    /** Forgets scripts, stored operations, emitted webhooks and counters; comes back up. */
    public void reset() {
        script.clear();
        webhooks.clear();
        payments.clear();
        refunds.clear();
        payouts.clear();
        down.set(false);
        mutatingCalls.set(0);
    }

    /** How many collect/refund/payout calls reached this provider (proves "never retried"). */
    public int mutatingCalls() {
        return mutatingCalls.get();
    }

    /** Provider-side truth for a payment: the customer paid in full, failed, or it expired. */
    public void settle(ProviderReference ref, PaymentStatus status) {
        payments.computeIfPresent(ref.value(), (k, r) ->
                new Record<>(status, raw(status), r.requested(), status == PaymentStatus.SUCCEEDED ? r.requested() : null));
    }

    /** Provider-side truth with an explicit paid amount (e.g. a provider reporting a different amount). */
    public void settle(ProviderReference ref, PaymentStatus status, long paidAmount) {
        payments.computeIfPresent(ref.value(), (k, r) ->
                new Record<>(status, raw(status), r.requested(), new Money(paidAmount, r.requested().currency())));
    }

    /** The customer paid, but only {@code paidAmount} (e.g. NabooPay {@code part_paid}). */
    public void settlePartially(ProviderReference ref, long paidAmount) {
        payments.computeIfPresent(ref.value(), (k, r) ->
                new Record<>(PaymentStatus.PENDING, "part_paid", r.requested(), new Money(paidAmount, r.requested().currency())));
    }

    public void settlePayout(ProviderReference ref, PayoutStatus status) {
        payouts.computeIfPresent(ref.value(), (k, r) -> new Record<>(status, raw(status), r.requested(), r.requested()));
    }

    public void settleRefund(ProviderReference ref, RefundStatus status) {
        refunds.computeIfPresent(ref.value(), (k, r) -> new Record<>(status, raw(status), r.requested(), r.requested()));
    }

    /** Reference of the payment created for Yoon's {@code attemptReference}, even if the caller never saw it. */
    public ProviderReference referenceFor(String attemptReference) {
        return new ProviderReference("fake_" + attemptReference);
    }

    // ------------------------------------------------------------------ webhooks

    /** Emits a signed callback carrying the payment's current provider-side status. */
    public InboundWebhook sendWebhook(ProviderReference ref) {
        Record<PaymentStatus> r = payments.get(ref.value());
        return sendWebhook(ref, r == null ? "unknown" : r.rawStatus());
    }

    /**
     * Emits a signed callback claiming any status — including one that contradicts the
     * provider's truth (late failure after success, a lie). Yoon must re-confirm, not believe.
     */
    public InboundWebhook sendWebhook(ProviderReference ref, String rawStatus) {
        byte[] body = ("{\"reference\":\"" + ref.value() + "\",\"status\":\"" + rawStatus + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        InboundWebhook hook = new InboundWebhook(Map.of(SIGNATURE_HEADER, List.of(sign(body)), "Content-Type", List.of("application/json")), body);
        webhooks.add(hook);
        return hook;
    }

    /** Emits the same callback twice, byte for byte. */
    public void sendDuplicateWebhook(ProviderReference ref) {
        InboundWebhook hook = sendWebhook(ref);
        webhooks.add(hook);
    }

    /** A callback with a wrong signature, as an attacker would send. */
    public InboundWebhook forgedWebhook(ProviderReference ref, String rawStatus) {
        byte[] body = ("{\"reference\":\"" + ref.value() + "\",\"status\":\"" + rawStatus + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        InboundWebhook hook = new InboundWebhook(Map.of(SIGNATURE_HEADER, List.of("0".repeat(64))), body);
        webhooks.add(hook);
        return hook;
    }

    /** Returns and clears every callback emitted so far, in order. */
    public List<InboundWebhook> drainWebhooks() {
        List<InboundWebhook> out = new ArrayList<>();
        InboundWebhook h;
        while ((h = webhooks.poll()) != null) {
            out.add(h);
        }
        return out;
    }

    // ------------------------------------------------------------------ SPI

    @Override
    public ProviderId id() {
        return id;
    }

    @Override
    public Capabilities capabilities() {
        return capabilities;
    }

    @Override
    public CallOutcome collect(CollectRequest request) {
        return mutate(request.attemptReference(), request.amount(), payments, PaymentStatus.PENDING,
                URI.create("https://fake.example/checkout/" + request.attemptReference()));
    }

    @Override
    public StatusResult<PaymentStatus> status(ProviderReference payment) {
        return query(payments, payment);
    }

    @Override
    public CallOutcome refund(RefundRequest request) {
        return mutate(request.attemptReference(), request.amount(), refunds, RefundStatus.PENDING, null);
    }

    @Override
    public StatusResult<RefundStatus> refundStatus(ProviderReference refund) {
        return query(refunds, refund);
    }

    @Override
    public CallOutcome payout(PayoutRequest request) {
        return mutate(request.attemptReference(), request.amount(), payouts, PayoutStatus.PROCESSING, null);
    }

    @Override
    public StatusResult<PayoutStatus> payoutStatus(ProviderReference payout) {
        return query(payouts, payout);
    }

    @Override
    public Optional<ProviderReference> lookup(Operation operation, String attemptReference) {
        if (down.get()) {
            return Optional.empty();
        }
        ProviderReference ref = referenceFor(attemptReference);
        Map<String, ? extends Record<?>> store = switch (operation) {
            case COLLECT -> payments;
            case REFUND -> refunds;
            case PAYOUT -> payouts;
        };
        return store.containsKey(ref.value()) ? Optional.of(ref) : Optional.empty();
    }

    @Override
    public WebhookVerification verify(InboundWebhook hook) {
        String sent = hook.header(SIGNATURE_HEADER);
        byte[] expected = sign(hook.rawBody()).getBytes(StandardCharsets.UTF_8);
        boolean valid = sent != null && MessageDigest.isEqual(expected, sent.getBytes(StandardCharsets.UTF_8));
        Matcher m = REFERENCE.matcher(new String(hook.rawBody(), StandardCharsets.UTF_8));
        return new WebhookVerification(valid, m.find() ? new ProviderReference(m.group(1)) : null);
    }

    // ------------------------------------------------------------------ internals

    private <S extends Enum<S>> CallOutcome mutate(String attempt, Money amount, Map<String, Record<S>> store,
                                                   S initial, URI checkoutUrl) {
        if (down.get()) {
            return new CallOutcome.Rejected("PROVIDER_UNAVAILABLE", "connection refused");
        }
        Behaviour behaviour = script.poll();
        if (behaviour == null) {
            behaviour = Behaviour.accept();
        }
        return switch (behaviour) {
            case Behaviour.Down d -> new CallOutcome.Rejected("PROVIDER_UNAVAILABLE", "connection refused");
            case Behaviour.Reject r -> {
                mutatingCalls.incrementAndGet();
                yield new CallOutcome.Rejected(r.code(), "rejected by fake provider");
            }
            case Behaviour.Hang h -> {
                mutatingCalls.incrementAndGet();
                sleep(h);
                yield new CallOutcome.Unknown("read timeout after " + h.duration());
            }
            case Behaviour.TimeoutAfterAccept t -> {
                mutatingCalls.incrementAndGet();
                create(attempt, amount, store, initial);
                yield new CallOutcome.Unknown("read timeout");
            }
            case Behaviour.Accept a -> {
                mutatingCalls.incrementAndGet();
                ProviderReference ref = create(attempt, amount, store, initial);
                yield new CallOutcome.Accepted(ref, checkoutUrl, null);
            }
        };
    }

    private <S extends Enum<S>> ProviderReference create(String attempt, Money amount, Map<String, Record<S>> store, S initial) {
        ProviderReference ref = referenceFor(attempt);
        store.putIfAbsent(ref.value(), new Record<>(initial, raw(initial), amount, null));
        return ref;
    }

    private <S extends Enum<S>> StatusResult<S> query(Map<String, Record<S>> store, ProviderReference ref) {
        if (down.get()) {
            return new StatusResult<>(null, null, null);
        }
        Record<S> r = store.get(ref.value());
        if (r == null) {
            return new StatusResult<>(null, "not_found", null);
        }
        return new StatusResult<>(r.status(), r.rawStatus(), r.paid());
    }

    private static String raw(Enum<?> status) {
        return status.name().toLowerCase();
    }

    private String sign(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(Behaviour.Hang h) {
        try {
            Thread.sleep(h.duration());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
