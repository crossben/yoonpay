package dev.yoonpay.server.api;

import dev.yoonpay.core.lifecycle.PaymentStatus;
import dev.yoonpay.core.provider.InboundWebhook;
import dev.yoonpay.core.provider.ProviderReference;
import dev.yoonpay.server.lifecycle.Alerts;
import dev.yoonpay.server.settlement.Reconciler;
import dev.yoonpay.server.webhook.InboundWebhookProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static dev.yoonpay.server.TestProviders.FAKE_ONE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Money-safety plan 2026-11, M1: a payment the provider confirms at another amount is flagged once
 * for a human — not re-checked and re-alerted on every sweep, never expired, never settled.
 */
class PaymentReviewTest extends ApiTest {

    @Autowired
    Reconciler reconciler;

    @Autowired
    InboundWebhookProcessor processor;

    @Autowired
    Alerts alerts;

    private Response pendingPayment() {
        Response r = post("shop", "/v1/payments", payment("wave"));
        assertThat(r.text("status")).isEqualTo("pending");
        return r;
    }

    private void age(String id, String age) {
        jdbc.sql("UPDATE payments SET created_at = now() - CAST(:a AS interval), updated_at = now() - CAST(:a AS interval) WHERE id = :id")
                .param("a", age).param("id", id).update();
    }

    private void callback(ProviderReference ref) {
        InboundWebhook hook = FAKE_ONE.sendWebhook(ref);
        postRaw("/v1/hooks/fakeone/" + app("shop").id(), hook.headers(), hook.rawBody());
        FAKE_ONE.drainWebhooks();
        processor.processPending(10);
    }

    private int mismatchEvents(String id) {
        int n = 0;
        for (var e : get("shop", "/v1/payments/" + id + "/events").json()) {
            if (e.path("detail").asString().startsWith("amount mismatch")) {
                n++;
            }
        }
        return n;
    }

    private long balance() {
        for (var b : get("shop", "/v1/balances").json().path("data")) {
            if (b.path("account").asString().equals("provider_balance:fakeone")) {
                return b.path("amount").asLong();
            }
        }
        return 0;
    }

    @Test
    void a_mismatched_payment_is_flagged_once_never_expires_and_an_operator_resolves_it() {
        Response p = pendingPayment();
        String id = p.text("id");
        FAKE_ONE.settle(new ProviderReference(p.text("provider_reference")), PaymentStatus.SUCCEEDED, 4000);
        double before = alerts.count(Alerts.Type.amount_mismatch);
        long credited = balance();

        for (int i = 0; i < 3; i++) {
            age(id, "2 hours"); // past the payment TTL too: it must not expire
            reconciler.sweepPayments();
        }

        Response flagged = get("shop", "/v1/payments/" + id);
        assertThat(flagged.text("status")).isEqualTo("pending");
        assertThat(flagged.json().path("needs_review").asBoolean()).isTrue();
        assertThat(alerts.count(Alerts.Type.amount_mismatch)).as("one alert, not one per sweep").isEqualTo(before + 1);
        assertThat(mismatchEvents(id)).as("one history entry").isEqualTo(1);
        assertThat(balance()).as("nothing credited").isEqualTo(credited);

        Response resolved = admin("POST", "/admin/v1/payments/" + id + "/resolve",
                Map.of("status", "succeeded", "note", "Customer paid the missing 1 000 XOF in cash"));

        assertThat(resolved.status()).isEqualTo(200);
        assertThat(resolved.text("status")).isEqualTo("succeeded");
        assertThat(resolved.json().path("needs_review").asBoolean()).isFalse();
        assertThat(balance()).as("the requested amount").isEqualTo(credited + 5000);
        assertThat(admin("POST", "/admin/v1/payments/" + id + "/resolve",
                Map.of("status", "failed", "note", "again")).text("code")).isEqualTo("already_final");
    }

    @Test
    void a_partial_payment_completed_later_settles_and_clears_its_flag() {
        Response p = pendingPayment();
        ProviderReference ref = new ProviderReference(p.text("provider_reference"));
        FAKE_ONE.settlePartially(ref, 3000);
        callback(ref);
        assertThat(get("shop", "/v1/payments/" + p.text("id")).json().path("needs_review").asBoolean()).isTrue();

        FAKE_ONE.settle(ref, PaymentStatus.SUCCEEDED);
        callback(ref);

        Response after = get("shop", "/v1/payments/" + p.text("id"));
        assertThat(after.text("status")).isEqualTo("succeeded");
        assertThat(after.json().path("needs_review").asBoolean()).isFalse();
    }

    @Test
    void an_operator_can_resolve_a_flagged_payment_as_failed() {
        Response p = pendingPayment();
        FAKE_ONE.settle(new ProviderReference(p.text("provider_reference")), PaymentStatus.SUCCEEDED, 9000);
        age(p.text("id"), "2 minutes");
        reconciler.sweepPayments();

        Response resolved = admin("POST", "/admin/v1/payments/" + p.text("id") + "/resolve",
                Map.of("status", "failed", "note", "Refunded the customer on the provider dashboard"));

        assertThat(resolved.text("status")).isEqualTo("failed");
        assertThat(resolved.json().path("failure").path("code").asString()).isEqualTo("resolved_failed");
        assertThat(admin("POST", "/admin/v1/payments/pay_nope/resolve",
                Map.of("status", "failed", "note", "x")).status()).isEqualTo(404);
    }
}
