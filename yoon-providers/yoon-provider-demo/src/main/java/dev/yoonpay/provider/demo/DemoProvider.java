package dev.yoonpay.provider.demo;

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
import dev.yoonpay.provider.support.Json;
import dev.yoonpay.provider.support.Signatures;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Currency;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A pretend provider for demos and the example shop. Collections open a checkout page served by
 * Yoon itself ({@code /demo/checkout/{reference}}) where the visitor pays or declines; payouts and
 * refunds succeed at once. No money ever moves. Behaves like a real provider where it matters:
 * signed callbacks, a status API, amounts reported back.
 */
public final class DemoProvider implements PaymentProvider {

    public static final ProviderId ID = new ProviderId("demo");
    public static final String SIGNATURE_HEADER = "X-Demo-Signature";
    private static final Currency XOF = Currency.getInstance("XOF");

    private final DemoBank bank;
    private final URI checkoutBase;
    private final String secret;
    private final Map<String, Money> transfers = new ConcurrentHashMap<>();

    public DemoProvider(DemoBank bank, URI checkoutBase, String secret) {
        this.bank = bank;
        this.checkoutBase = checkoutBase;
        this.secret = secret;
    }

    @Override
    public ProviderId id() {
        return ID;
    }

    @Override
    public Capabilities capabilities() {
        Set<Capability> caps = new HashSet<>();
        for (Operation op : Operation.values()) {
            for (String m : Set.of("wave", "orange_money", "free_money", "card")) {
                caps.add(new Capability(op, "SN", m, XOF));
            }
        }
        return new Capabilities(caps);
    }

    @Override
    public CallOutcome collect(CollectRequest r) {
        bank.open(new DemoBank.Checkout(r.attemptReference(), r.amount(), r.description(), r.returnUrl(),
                r.callbackUrl(), DemoBank.State.PENDING));
        return new CallOutcome.Accepted(new ProviderReference(r.attemptReference()),
                checkoutBase.resolve("/demo/checkout/" + r.attemptReference()), null);
    }

    @Override
    public StatusResult<PaymentStatus> status(ProviderReference payment) {
        return bank.find(payment.value())
                .map(c -> switch (c.state()) {
                    case PENDING -> new StatusResult<>(PaymentStatus.PENDING, "pending", null);
                    case PAID -> new StatusResult<>(PaymentStatus.SUCCEEDED, "paid", c.amount());
                    case DECLINED -> new StatusResult<>(PaymentStatus.FAILED, "declined", null);
                })
                .orElse(new StatusResult<>(null, "not_found", null));
    }

    @Override
    public CallOutcome refund(RefundRequest r) {
        transfers.put(r.attemptReference(), r.amount());
        return new CallOutcome.Accepted(new ProviderReference(r.attemptReference()), null, null);
    }

    @Override
    public StatusResult<RefundStatus> refundStatus(ProviderReference refund) {
        Money m = transfers.get(refund.value());
        return m == null ? new StatusResult<>(null, "not_found", null) : new StatusResult<>(RefundStatus.REFUNDED, "refunded", m);
    }

    @Override
    public CallOutcome payout(PayoutRequest r) {
        transfers.put(r.attemptReference(), r.amount());
        return new CallOutcome.Accepted(new ProviderReference(r.attemptReference()), null, null);
    }

    @Override
    public StatusResult<PayoutStatus> payoutStatus(ProviderReference payout) {
        Money m = transfers.get(payout.value());
        return m == null ? new StatusResult<>(null, "not_found", null) : new StatusResult<>(PayoutStatus.PAID, "paid", m);
    }

    @Override
    public Optional<ProviderReference> lookup(Operation operation, String attemptReference) {
        boolean known = operation == Operation.COLLECT ? bank.find(attemptReference).isPresent() : transfers.containsKey(attemptReference);
        return known ? Optional.of(new ProviderReference(attemptReference)) : Optional.empty();
    }

    @Override
    public WebhookVerification verify(InboundWebhook hook) {
        boolean valid = Signatures.hexEquals(Signatures.hmacSha256Hex(secret, hook.rawBody()), hook.header(SIGNATURE_HEADER));
        String ref = Json.text(Json.parse(new String(hook.rawBody(), StandardCharsets.UTF_8)), "reference").orElse(null);
        return new WebhookVerification(valid, ref == null ? null : new ProviderReference(ref));
    }

    /** The callback the demo checkout page sends after the visitor decides. */
    public byte[] callbackBody(DemoBank.Checkout c) {
        return Json.write(Map.of("reference", c.reference(), "status", c.state().name().toLowerCase()))
                .getBytes(StandardCharsets.UTF_8);
    }

    public String sign(byte[] body) {
        return Signatures.hmacSha256Hex(secret, body);
    }
}
