package dev.yoonpay.testkit;

import dev.yoonpay.core.lifecycle.PaymentStatus;
import dev.yoonpay.core.lifecycle.PayoutStatus;
import dev.yoonpay.core.money.Money;
import dev.yoonpay.core.provider.CallOutcome;
import dev.yoonpay.core.provider.CollectRequest;
import dev.yoonpay.core.provider.InboundWebhook;
import dev.yoonpay.core.provider.PayoutRequest;
import dev.yoonpay.core.provider.ProviderReference;
import dev.yoonpay.core.provider.StatusResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FakeProviderTest {

    private final FakeProvider fake = new FakeProvider("fake", "whsec_test");

    private static CollectRequest collect(String attempt) {
        return new CollectRequest(attempt, Money.of(5000, "XOF"), "SN", "wave", "+221770000000", "order", null, null);
    }

    @Test
    void accepts_by_default_and_reports_pending_until_settled() {
        var accepted = (CallOutcome.Accepted) fake.collect(collect("a1"));

        assertThat(fake.status(accepted.reference()).status()).isEqualTo(PaymentStatus.PENDING);

        fake.settle(accepted.reference(), PaymentStatus.SUCCEEDED);
        StatusResult<PaymentStatus> s = fake.status(accepted.reference());
        assertThat(s.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(s.confirmedAmount()).isEqualTo(Money.of(5000, "XOF"));
    }

    @Test
    void scripted_rejection_is_definite() {
        fake.script(Behaviour.reject("INSUFFICIENT_FUNDS"));

        assertThat(fake.collect(collect("a1"))).isEqualTo(new CallOutcome.Rejected("INSUFFICIENT_FUNDS", "rejected by fake provider"));
    }

    @Test
    void down_rejects_without_reaching_the_provider_and_status_queries_get_no_answer() {
        var accepted = (CallOutcome.Accepted) fake.collect(collect("a1"));
        fake.setDown(true);

        assertThat(fake.collect(collect("a2"))).isInstanceOf(CallOutcome.Rejected.class);
        assertThat(fake.status(accepted.reference()).status()).isNull();
        assertThat(fake.mutatingCalls()).isEqualTo(1);
    }

    @Test
    void hang_blocks_then_reports_unknown_and_creates_nothing() {
        fake.script(Behaviour.hang(Duration.ofMillis(50)));
        long start = System.nanoTime();

        CallOutcome outcome = fake.collect(collect("a1"));

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(50));
        assertThat(outcome).isInstanceOf(CallOutcome.Unknown.class);
        assertThat(fake.status(fake.referenceFor("a1")).rawStatus()).isEqualTo("not_found");
    }

    @Test
    void timeout_after_accept_hides_a_payment_that_really_exists() {
        fake.script(Behaviour.timeoutAfterAccept());

        assertThat(fake.collect(collect("a1"))).isInstanceOf(CallOutcome.Unknown.class);
        assertThat(fake.status(fake.referenceFor("a1")).status()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void partial_payment_is_reported_with_the_amount_actually_paid() {
        var accepted = (CallOutcome.Accepted) fake.collect(collect("a1"));

        fake.settlePartially(accepted.reference(), 3000);

        StatusResult<PaymentStatus> s = fake.status(accepted.reference());
        assertThat(s.rawStatus()).isEqualTo("part_paid");
        assertThat(s.confirmedAmount()).isEqualTo(Money.of(3000, "XOF"));
    }

    @Test
    void webhooks_are_signed_over_the_raw_body_and_forgeries_fail_verification() {
        ProviderReference ref = ((CallOutcome.Accepted) fake.collect(collect("a1"))).reference();

        InboundWebhook genuine = fake.sendWebhook(ref, "succeeded");
        InboundWebhook forged = fake.forgedWebhook(ref, "succeeded");

        assertThat(fake.verify(genuine).signatureValid()).isTrue();
        assertThat(fake.verify(genuine).reference()).isEqualTo(ref);
        assertThat(fake.verify(forged).signatureValid()).isFalse();
    }

    @Test
    void a_webhook_from_another_secret_does_not_verify() {
        FakeProvider other = new FakeProvider("fake", "another_secret");
        InboundWebhook hook = other.sendWebhook(new ProviderReference("fake_x"), "succeeded");

        assertThat(fake.verify(hook).signatureValid()).isFalse();
    }

    @Test
    void duplicate_and_late_webhooks_are_emitted_in_order() {
        ProviderReference ref = ((CallOutcome.Accepted) fake.collect(collect("a1"))).reference();
        fake.settle(ref, PaymentStatus.SUCCEEDED);

        fake.sendDuplicateWebhook(ref);
        fake.sendWebhook(ref, "failed"); // late failure after success: a lie Yoon must ignore

        List<InboundWebhook> hooks = fake.drainWebhooks();
        assertThat(hooks).hasSize(3);
        assertThat(hooks.get(0).rawBody()).isEqualTo(hooks.get(1).rawBody());
        assertThat(fake.drainWebhooks()).isEmpty();
        assertThat(fake.status(ref).status()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void payouts_follow_the_same_script_and_count_every_attempt() {
        fake.script(Behaviour.timeoutAfterAccept());
        PayoutRequest req = new PayoutRequest("p1", Money.of(10000, "XOF"), "SN", "wave", "+221770000000", null);

        assertThat(fake.payout(req)).isInstanceOf(CallOutcome.Unknown.class);
        ProviderReference ref = fake.referenceFor("p1");
        assertThat(fake.payoutStatus(ref).status()).isEqualTo(PayoutStatus.PROCESSING);

        fake.settlePayout(ref, PayoutStatus.PAID);
        assertThat(fake.payoutStatus(ref).status()).isEqualTo(PayoutStatus.PAID);
        assertThat(fake.mutatingCalls()).isEqualTo(1);
    }
}
