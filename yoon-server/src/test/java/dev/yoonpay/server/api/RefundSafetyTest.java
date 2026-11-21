package dev.yoonpay.server.api;

import dev.yoonpay.core.lifecycle.PayoutStatus;
import dev.yoonpay.core.lifecycle.RefundStatus;
import dev.yoonpay.core.provider.InboundWebhook;
import dev.yoonpay.core.provider.ProviderReference;
import dev.yoonpay.server.lifecycle.Alerts;
import dev.yoonpay.server.refund.RefundOperations;
import dev.yoonpay.server.refund.RefundsCommand;
import dev.yoonpay.server.settlement.Reconciler;
import dev.yoonpay.server.webhook.InboundWebhookProcessor;
import dev.yoonpay.testkit.Behaviour;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.Map;

import static dev.yoonpay.server.TestProviders.FAKE_ONE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Money-safety plan 2026-11 (C1, H1; PR #1 by @mooder1): a refund the provider may still confirm
 * keeps its amount, late refunds and over-refunds raise alerts, and refunds and payouts never
 * settle on an amount the provider did not confirm.
 */
class RefundSafetyTest extends ApiTest {

    @Autowired
    Reconciler reconciler;

    @Autowired
    InboundWebhookProcessor processor;

    @Autowired
    Alerts alerts;

    @Autowired
    RefundOperations operations;

    private String succeededPayment() {
        String id = post("shop", "/v1/payments", payment("wave")).text("id");
        jdbc.sql("UPDATE payments SET status = 'SUCCEEDED' WHERE id = :id").param("id", id).update();
        return id;
    }

    private void age(String table, String id) {
        jdbc.sql("UPDATE " + table + " SET updated_at = now() - interval '10 minutes', created_at = now() - interval '10 minutes' WHERE id = :id")
                .param("id", id).update();
    }

    private Response refund(String paymentId, long amount) {
        return post("shop", "/v1/payments/" + paymentId + "/refunds", Map.of("amount", amount));
    }

    private Response getRefund(String id) {
        return get("shop", "/v1/refunds/" + id);
    }

    /** The provider took the refund, then its status API said it failed: Yoon marks it failed. */
    private Response refundFailedAfterTheProviderSawIt(String paymentId) {
        Response r = refund(paymentId, 5000);
        assertThat(r.text("status")).isEqualTo("pending");
        FAKE_ONE.settleRefund(new ProviderReference(r.text("provider_reference")), RefundStatus.FAILED);
        age("refunds", r.text("id"));
        reconciler.sweepRefunds();
        Response failed = getRefund(r.text("id"));
        assertThat(failed.text("status")).isEqualTo("failed");
        return failed;
    }

    /** The provider changes its mind: the refund went through after all, and says so by callback. */
    private void providerConfirmsLate(Response refund) {
        ProviderReference ref = new ProviderReference(refund.text("provider_reference"));
        FAKE_ONE.settleRefund(ref, RefundStatus.REFUNDED);
        InboundWebhook hook = FAKE_ONE.sendWebhook(ref);
        postRaw("/v1/hooks/fakeone/" + app("shop").id(), hook.headers(), hook.rawBody());
        FAKE_ONE.drainWebhooks();
        processor.processPending(10);
    }

    private long refundedInLedger(String paymentId) {
        return jdbc.sql("SELECT coalesce(sum(amount), 0) FROM refunds WHERE payment_id = :p AND status = 'REFUNDED'")
                .param("p", paymentId).query(Long.class).single();
    }

    @Test
    void a_failed_refund_the_provider_saw_holds_its_amount_so_a_late_success_cannot_double_refund() {
        String payment = succeededPayment();
        Response failed = refundFailedAfterTheProviderSawIt(payment);
        assertThat(failed.json().path("holds_amount").asBoolean()).isTrue();

        Response second = refund(payment, 5000);
        assertThat(second.status()).isEqualTo(422);
        assertThat(second.text("code")).isEqualTo("refund_exceeds_payment");

        double late = alerts.count(Alerts.Type.late_refund);
        double over = alerts.count(Alerts.Type.refund_exceeds_payment);
        providerConfirmsLate(failed);

        assertThat(getRefund(failed.text("id")).text("status")).isEqualTo("refunded");
        assertThat(alerts.count(Alerts.Type.late_refund)).isEqualTo(late + 1);
        assertThat(alerts.count(Alerts.Type.refund_exceeds_payment)).as("only 5000 left, once").isEqualTo(over);
        assertThat(refundedInLedger(payment)).isEqualTo(5000);
    }

    @Test
    void a_refund_rejected_before_the_provider_saw_it_holds_nothing() {
        String payment = succeededPayment();
        FAKE_ONE.script(Behaviour.reject("REFUND_WINDOW_CLOSED"));

        Response rejected = refund(payment, 5000);

        assertThat(rejected.text("status")).isEqualTo("failed");
        assertThat(rejected.json().path("holds_amount").asBoolean()).isFalse();
        assertThat(refund(payment, 5000).status()).isEqualTo(201);
    }

    @Test
    void an_operator_releases_the_amount_and_a_late_success_after_that_raises_refund_exceeds_payment() {
        String payment = succeededPayment();
        Response failed = refundFailedAfterTheProviderSawIt(payment);

        Response released = admin("POST", "/admin/v1/refunds/" + failed.text("id") + "/release",
                Map.of("note", "Provider confirms it failed"));
        assertThat(released.status()).isEqualTo(200);
        assertThat(released.json().path("holds_amount").asBoolean()).isFalse();
        assertThat(get("shop", "/v1/refunds/" + failed.text("id") + "/events").body()).contains("amount released by operator");

        Response second = refund(payment, 5000);
        assertThat(second.status()).isEqualTo(201);
        FAKE_ONE.settleRefund(new ProviderReference(second.text("provider_reference")), RefundStatus.REFUNDED);
        age("refunds", second.text("id"));
        reconciler.sweepRefunds();
        assertThat(getRefund(second.text("id")).text("status")).isEqualTo("refunded");

        double over = alerts.count(Alerts.Type.refund_exceeds_payment);
        providerConfirmsLate(failed);

        assertThat(alerts.count(Alerts.Type.refund_exceeds_payment)).isEqualTo(over + 1);
        assertThat(refundedInLedger(payment)).as("both applied: the money really left twice").isEqualTo(10000);
        assertThat(admin("POST", "/admin/v1/refunds/" + failed.text("id") + "/release", Map.of("note", "again"))
                .text("code")).isEqualTo("nothing_to_release");
    }

    @Test
    void only_a_failed_refund_the_provider_saw_can_be_released() {
        String payment = succeededPayment();
        Response pending = refund(payment, 1000);

        Response r = admin("POST", "/admin/v1/refunds/" + pending.text("id") + "/release", Map.of("note", "no"));

        assertThat(r.status()).isEqualTo(422);
        assertThat(r.text("code")).isEqualTo("nothing_to_release");
        assertThat(admin("POST", "/admin/v1/refunds/ref_nope/release", Map.of("note", "x")).status()).isEqualTo(404);
    }

    @Test
    void a_refund_confirmed_at_another_amount_is_never_settled_and_alerts_once() {
        String payment = succeededPayment();
        Response r = refund(payment, 5000);
        FAKE_ONE.settleRefund(new ProviderReference(r.text("provider_reference")), RefundStatus.REFUNDED, 4000);
        double before = alerts.count(Alerts.Type.amount_mismatch);

        age("refunds", r.text("id"));
        reconciler.sweepRefunds();
        age("refunds", r.text("id"));
        reconciler.sweepRefunds();

        Response after = getRefund(r.text("id"));
        assertThat(after.text("status")).isEqualTo("pending");
        assertThat(after.json().path("needs_review").asBoolean()).isTrue();
        assertThat(alerts.count(Alerts.Type.amount_mismatch)).as("flagged once").isEqualTo(before + 1);
        assertThat(refundedInLedger(payment)).isZero();

        Response resolved = admin("POST", "/admin/v1/refunds/" + r.text("id") + "/resolve",
                Map.of("status", "refunded", "note", "Checked on the dashboard"));
        assertThat(resolved.text("status")).isEqualTo("refunded");
        assertThat(resolved.json().path("needs_review").asBoolean()).isFalse();
    }

    @Test
    void a_payout_confirmed_at_another_amount_is_never_paid_and_alerts_once() {
        Response po = post("shop", "/v1/payouts", Map.of("amount", 8000, "currency", "XOF", "country", "SN",
                "method", "wave", "recipient", Map.of("phone", "+221771234567")));
        FAKE_ONE.settlePayout(new ProviderReference(po.text("provider_reference")), PayoutStatus.PAID, 7500);
        double before = alerts.count(Alerts.Type.amount_mismatch);

        age("payouts", po.text("id"));
        reconciler.sweepPayouts();
        age("payouts", po.text("id"));
        reconciler.sweepPayouts();

        Response after = get("shop", "/v1/payouts/" + po.text("id"));
        assertThat(after.text("status")).isEqualTo("processing");
        assertThat(after.json().path("needs_review").asBoolean()).isTrue();
        assertThat(alerts.count(Alerts.Type.amount_mismatch)).isEqualTo(before + 1);
    }

    @Test
    void the_refunds_command_releases_and_explains_its_usage() {
        String payment = succeededPayment();
        Response failed = refundFailedAfterTheProviderSawIt(payment);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int ok = RefundsCommand.run(new String[]{"refunds", "release", failed.text("id"), "Provider", "confirms"},
                operations, new PrintStream(out));
        int usage = RefundsCommand.run(new String[]{"refunds", "nope"}, operations, new PrintStream(out));

        assertThat(ok).isZero();
        assertThat(usage).isEqualTo(2);
        assertThat(out.toString()).contains(failed.text("id") + " failed 5000 XOF").contains("Usage:");
        assertThat(getRefund(failed.text("id")).json().path("holds_amount").asBoolean()).isFalse();
    }
}
