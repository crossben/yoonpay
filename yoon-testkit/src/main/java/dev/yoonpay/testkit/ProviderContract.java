package dev.yoonpay.testkit;

import dev.yoonpay.core.money.Money;
import dev.yoonpay.core.provider.CallOutcome;
import dev.yoonpay.core.provider.Capability;
import dev.yoonpay.core.provider.CollectRequest;
import dev.yoonpay.core.provider.InboundWebhook;
import dev.yoonpay.core.provider.Operation;
import dev.yoonpay.core.provider.PaymentProvider;
import dev.yoonpay.core.provider.PayoutRequest;
import dev.yoonpay.core.provider.ProviderReference;
import dev.yoonpay.core.provider.RefundRequest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The rules every provider adapter must keep, whatever the provider. A provider module's test
 * extends this class and says how to make its simulated provider misbehave; the tests here then
 * check the money-safety rules generically:
 * <ul>
 *   <li>a request that provably never left (connection refused) is {@code Rejected}, so it may
 *       fail over;</li>
 *   <li>once the money-moving request reached the provider, a 5xx, a dropped connection or a
 *       timeout is {@code Unknown}, never {@code Rejected} and never {@code Accepted};</li>
 *   <li>a status query with no usable answer says "no answer" (null), never a guess;</li>
 *   <li>an operation the provider does not offer is refused without any request;</li>
 *   <li>a callback with a missing or wrong signature is invalid, and odd bodies never throw.</li>
 * </ul>
 * Requests are built from the adapter's own capabilities, with both a phone number and a PI alias,
 * so every adapter gets what it needs.
 */
public abstract class ProviderContract {

    /** The adapter, pointed at the simulated provider. */
    protected abstract PaymentProvider provider();

    /** The adapter with a request timeout far shorter than {@link #hangEverything()}'s delay. */
    protected abstract PaymentProvider providerWithShortTimeout();

    /** The adapter pointed at an address where nothing listens (connection refused). */
    protected abstract PaymentProvider unreachableProvider();

    /** Every request to the simulated provider answers with this HTTP status and an empty JSON body. */
    protected abstract void answerEverything(int status);

    /** Every request to the simulated provider has its connection dropped. */
    protected abstract void dropEverything();

    /** Every request to the simulated provider answers only after a long delay. */
    protected abstract void hangEverything();

    /**
     * How many money-moving requests (creating a payment, payout or refund — not reads, not
     * token requests) reached the simulated provider so far in this test.
     */
    protected abstract int moneyMovingRequestsReceived();

    // ------------------------------------------------------------------ rules

    @Test
    void a_request_that_never_left_is_rejected_so_it_may_fail_over() {
        PaymentProvider p = unreachableProvider();
        for (Operation op : offered(p)) {
            assertThat(call(p, op)).as(op + " with connection refused").isInstanceOf(CallOutcome.Rejected.class);
        }
    }

    @Test
    void a_server_error_after_sending_is_unknown() {
        answerEverything(500);
        checkNeverGuessed(provider(), "HTTP 500");
    }

    @Test
    void a_dropped_connection_after_sending_is_unknown() {
        dropEverything();
        checkNeverGuessed(provider(), "dropped connection");
    }

    @Test
    void a_timeout_after_sending_is_unknown() {
        hangEverything();
        checkNeverGuessed(providerWithShortTimeout(), "timeout");
    }

    @Test
    void a_status_without_a_usable_answer_is_no_answer() {
        answerEverything(500);
        PaymentProvider p = provider();
        ProviderReference ref = new ProviderReference("ref_contract");
        assertThat(p.status(ref).status()).as("payment status").isNull();
        assertThat(p.refundStatus(ref).status()).as("refund status").isNull();
        assertThat(p.payoutStatus(ref).status()).as("payout status").isNull();
        assertThat(p.lookup(Operation.COLLECT, "att_contract")).isEqualTo(Optional.empty());
    }

    @Test
    void an_operation_not_offered_is_refused_without_a_request() {
        PaymentProvider p = provider();
        for (Operation op : Operation.values()) {
            if (!offered(p).contains(op)) {
                int before = moneyMovingRequestsReceived();
                assertThat(call(p, op)).as(op + " is not offered").isInstanceOf(CallOutcome.Rejected.class);
                assertThat(moneyMovingRequestsReceived()).as(op + " sent nothing").isEqualTo(before);
            }
        }
    }

    @Test
    void callbacks_without_a_valid_signature_are_invalid() {
        PaymentProvider p = provider();
        byte[] body = """
                {"id":"evt_x","type":"x","data":{"id":"ref_x","object":{"id":"ref_x"},"reference":"ref_x","txId":"ref_x"}}"""
                .getBytes(StandardCharsets.UTF_8);
        assertThat(p.verify(new InboundWebhook(Map.of(), body)).signatureValid()).as("no signature").isFalse();
        Map<String, List<String>> forged = Map.of(
                "X-Signature", List.of("deadbeef"), "Wave-Signature", List.of("t=1,v1=deadbeef"),
                "Stripe-Signature", List.of("t=1,v1=deadbeef"), "x-webhook-signature", List.of("deadbeef"),
                "x-token", List.of("deadbeef"), "Authorization", List.of("Bearer deadbeef"));
        assertThat(p.verify(new InboundWebhook(forged, body)).signatureValid()).as("forged signatures").isFalse();
    }

    @Test
    void odd_callback_bodies_never_throw() {
        PaymentProvider p = provider();
        for (String body : List.of("", "not json", "[]", "{}", "{\"data\":null}", "a=b&c")) {
            assertThatCode(() -> p.verify(new InboundWebhook(Map.of(), body.getBytes(StandardCharsets.UTF_8))))
                    .as("body " + body).doesNotThrowAnyException();
        }
    }

    // ------------------------------------------------------------------ helpers

    private void checkNeverGuessed(PaymentProvider p, String what) {
        for (Operation op : offered(p)) {
            int before = moneyMovingRequestsReceived();
            CallOutcome outcome = call(p, op);
            boolean sent = moneyMovingRequestsReceived() > before;
            assertThat(outcome).as(op + " after " + what).isNotInstanceOf(CallOutcome.Accepted.class);
            if (sent) {
                assertThat(outcome).as(op + " reached the provider, then " + what + ": the money may have moved")
                        .isInstanceOf(CallOutcome.Unknown.class);
            }
        }
    }

    private static List<Operation> offered(PaymentProvider p) {
        return List.of(Operation.values()).stream()
                .filter(op -> capability(p, op).isPresent()).toList();
    }

    private static Optional<Capability> capability(PaymentProvider p, Operation op) {
        return p.capabilities().supported().stream().filter(c -> c.operation() == op)
                .sorted(java.util.Comparator.comparing(Capability::toString)).findFirst();
    }

    private static final String PHONE = "+221771234567";
    private static final String ALIAS = "c0ffee00-0000-4000-8000-000000000001";
    private static final URI RETURN = URI.create("https://shop.example/return");
    private static final URI CALLBACK = URI.create("https://yoon.example/v1/hooks/contract/app");

    private static CallOutcome call(PaymentProvider p, Operation op) {
        Function<Capability, CallOutcome> run = switch (op) {
            case COLLECT -> c -> p.collect(new CollectRequest("att_contract", new Money(1000, c.currency()), c.country(),
                    c.method(), PHONE, "Contract test", RETURN, CALLBACK, ALIAS));
            case PAYOUT -> c -> p.payout(new PayoutRequest("po_contract", new Money(1000, c.currency()), c.country(),
                    c.method(), PHONE, CALLBACK, ALIAS));
            case REFUND -> c -> p.refund(new RefundRequest("rf_contract", new ProviderReference("ref_contract"),
                    new Money(1000, c.currency()), "contract test"));
        };
        Capability c = capability(p, op).orElseGet(() -> fallback(p, op));
        return run.apply(c);
    }

    /** For an operation the provider does not offer: any of its capabilities, re-labelled. */
    private static Capability fallback(PaymentProvider p, Operation op) {
        Capability any = p.capabilities().supported().stream().findFirst()
                .orElse(new Capability(op, "SN", "wave", java.util.Currency.getInstance("XOF")));
        return new Capability(op, any.country(), any.method(), any.currency());
    }
}
