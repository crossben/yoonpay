package dev.yoonpay.server.api;

import dev.yoonpay.core.lifecycle.PaymentStatus;
import dev.yoonpay.core.lifecycle.PayoutStatus;
import dev.yoonpay.core.lifecycle.RefundStatus;
import dev.yoonpay.core.provider.ProviderReference;
import dev.yoonpay.server.lifecycle.Alerts;
import dev.yoonpay.server.settlement.Reconciler;
import dev.yoonpay.testkit.Behaviour;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static dev.yoonpay.server.TestProviders.FAKE_ONE;
import static org.assertj.core.api.Assertions.assertThat;

/** Stuck payments, refunds and payouts settle without human action — except payouts that stay unknown. */
class ReconciliationTest extends ApiTest {

    @Autowired
    Reconciler reconciler;

    @Autowired
    Alerts alerts;

    /** Makes a row look {@code age} old to the sweep. */
    private void age(String table, String id, Duration age) {
        jdbc.sql("UPDATE " + table + " SET created_at = now() - make_interval(secs => :s), updated_at = now() - make_interval(secs => :s) WHERE id = :id")
                .param("s", age.toSeconds()).param("id", id).update();
    }

    private String paymentStatus(String id) {
        return get("shop", "/v1/payments/" + id).text("status");
    }

    private long account(String name) {
        for (var b : get("shop", "/v1/balances").json().path("data")) {
            if (b.path("account").asString().equals(name)) {
                return b.path("amount").asLong();
            }
        }
        return 0;
    }

    @Test
    void a_payment_whose_callback_never_came_is_settled_by_the_sweep() {
        Response p = post("shop", "/v1/payments", payment("wave"));
        FAKE_ONE.settle(new ProviderReference(p.text("provider_reference")), PaymentStatus.SUCCEEDED);
        age("payments", p.text("id"), Duration.ofMinutes(2));

        reconciler.sweepPayments();

        assertThat(paymentStatus(p.text("id"))).isEqualTo("succeeded");
        var events = get("shop", "/v1/payments/" + p.text("id") + "/events").json();
        assertThat(events.get(events.size() - 1).path("cause").asString()).isEqualTo("sweep");
    }

    @Test
    void a_payment_whose_creation_answer_was_lost_is_found_and_settled() {
        FAKE_ONE.script(Behaviour.timeoutAfterAccept());
        Response p = post("shop", "/v1/payments", payment("wave"));
        assertThat(p.text("provider_reference")).isNull();
        String attempt = jdbc.sql("SELECT id FROM payment_attempts WHERE payment_id = :p").param("p", p.text("id"))
                .query(String.class).single();
        FAKE_ONE.settle(FAKE_ONE.referenceFor(attempt), PaymentStatus.SUCCEEDED);
        age("payments", p.text("id"), Duration.ofMinutes(2));

        reconciler.sweepPayments();

        Response after = get("shop", "/v1/payments/" + p.text("id"));
        assertThat(after.text("status")).isEqualTo("succeeded");
        assertThat(after.text("provider_reference")).isEqualTo(FAKE_ONE.referenceFor(attempt).value());
    }

    @Test
    void an_unpaid_payment_expires_after_its_ttl_and_a_late_success_is_still_recovered() {
        Response p = post("shop", "/v1/payments", payment("wave"));
        String id = p.text("id");
        age("payments", id, Duration.ofMinutes(31));

        reconciler.sweepPayments();
        assertThat(paymentStatus(id)).isEqualTo("expired");

        // The customer paid after all.
        FAKE_ONE.settle(new ProviderReference(p.text("provider_reference")), PaymentStatus.SUCCEEDED);
        double lateBefore = alerts.count(Alerts.Type.late_success);
        age("payments", id, Duration.ofMinutes(11));

        reconciler.recheckFinalPayments();

        assertThat(paymentStatus(id)).isEqualTo("succeeded");
        assertThat(alerts.count(Alerts.Type.late_success)).isEqualTo(lateBefore + 1);
        assertThat(get("shop", "/v1/events?resource_id=" + id).json().path("data"))
                .extracting(e -> e.path("type").asString())
                .containsExactlyInAnyOrder("payment.expired", "payment.succeeded");
    }

    @Test
    void a_payment_is_not_expired_while_the_provider_is_unreachable_until_enough_checks_failed() {
        Response p = post("shop", "/v1/payments", payment("wave"));
        String id = p.text("id");
        age("payments", id, Duration.ofMinutes(31));
        FAKE_ONE.setDown(true);

        for (int i = 0; i < 4; i++) {
            reconciler.sweepPayments();
            assertThat(paymentStatus(id)).isEqualTo("pending");
        }
        reconciler.sweepPayments();

        assertThat(paymentStatus(id)).isEqualTo("expired");
    }

    @Test
    void a_young_payment_is_left_alone() {
        Response p = post("shop", "/v1/payments", payment("wave"));
        FAKE_ONE.settle(new ProviderReference(p.text("provider_reference")), PaymentStatus.SUCCEEDED);

        reconciler.sweepPayments();

        assertThat(paymentStatus(p.text("id"))).isEqualTo("pending");
    }

    @Test
    void a_creation_interrupted_before_any_call_fails_and_one_interrupted_after_a_call_waits() {
        String never = "pay_interrupted_" + UUID.randomUUID().toString().replace("-", "");
        String sent = "pay_interrupted_" + UUID.randomUUID().toString().replace("-", "");
        for (String id : new String[]{never, sent}) {
            jdbc.sql("""
                            INSERT INTO payments (id, application_id, status, amount, currency, country, method)
                            VALUES (:id, :app, 'CREATED', 1000, 'XOF', 'SN', 'wave')""")
                    .param("id", id).param("app", app("shop").id()).update();
            age("payments", id, Duration.ofMinutes(2));
        }
        jdbc.sql("INSERT INTO payment_attempts (id, payment_id, provider) VALUES (:a, :p, 'fakeone')")
                .param("a", "att_" + UUID.randomUUID()).param("p", sent).update();

        reconciler.sweepPayments();

        assertThat(paymentStatus(never)).isEqualTo("failed");
        assertThat(paymentStatus(sent)).isEqualTo("pending");
        assertThat(get("shop", "/v1/payments/" + sent).text("provider")).isEqualTo("fakeone");
    }

    @Test
    void an_unknown_payout_is_found_and_paid_out_of_the_reservation() {
        FAKE_ONE.script(Behaviour.timeoutAfterAccept());
        Response po = post("shop", "/v1/payouts", Map.of("amount", 8000, "currency", "XOF", "country", "SN",
                "method", "wave", "recipient", Map.of("phone", "+221771234567")));
        assertThat(po.text("status")).isEqualTo("unknown");
        long reserved = account("payout_reserved:fakeone");
        FAKE_ONE.settlePayout(FAKE_ONE.referenceFor(po.text("id")), PayoutStatus.PAID);
        age("payouts", po.text("id"), Duration.ofMinutes(2));

        reconciler.sweepPayouts();

        assertThat(get("shop", "/v1/payouts/" + po.text("id")).text("status")).isEqualTo("paid");
        assertThat(account("payout_reserved:fakeone")).isEqualTo(reserved - 8000);
        assertThat(FAKE_ONE.mutatingCalls()).as("never re-sent").isEqualTo(1);
    }

    @Test
    void a_failed_payout_releases_its_reservation() {
        Response po = post("shop", "/v1/payouts", Map.of("amount", 6000, "currency", "XOF", "country", "SN",
                "method", "wave", "recipient", Map.of("phone", "+221771234567")));
        long reserved = account("payout_reserved:fakeone");
        FAKE_ONE.settlePayout(new ProviderReference(po.text("provider_reference")), PayoutStatus.FAILED);
        age("payouts", po.text("id"), Duration.ofMinutes(2));

        reconciler.sweepPayouts();

        assertThat(get("shop", "/v1/payouts/" + po.text("id")).text("status")).isEqualTo("failed");
        assertThat(account("payout_reserved:fakeone")).isEqualTo(reserved - 6000);
    }

    @Test
    void a_payout_that_stays_unknown_is_escalated_to_a_human_who_resolves_it() {
        FAKE_ONE.script(Behaviour.hang(Duration.ofMillis(10))); // never created at the provider: lookup finds nothing
        Response po = post("shop", "/v1/payouts", Map.of("amount", 9000, "currency", "XOF", "country", "SN",
                "method", "wave", "recipient", Map.of("phone", "+221771234567")));
        String id = po.text("id");
        double before = alerts.count(Alerts.Type.payout_needs_review);
        age("payouts", id, Duration.ofHours(25));

        reconciler.sweepPayouts();
        reconciler.sweepPayouts(); // flagged once, not twice

        Response flagged = get("shop", "/v1/payouts/" + id);
        assertThat(flagged.text("status")).isEqualTo("unknown");
        assertThat(flagged.json().path("needs_review").asBoolean()).isTrue();
        assertThat(alerts.count(Alerts.Type.payout_needs_review)).isEqualTo(before + 1);
        assertThat(admin("GET", "/admin/v1/payouts", null).json().path("data"))
                .extracting(p -> p.path("id").asString()).contains(id);

        long reserved = account("payout_reserved:fakeone");
        Response resolved = admin("POST", "/admin/v1/payouts/" + id + "/resolve",
                Map.of("status", "failed", "note", "Not on the provider dashboard"));

        assertThat(resolved.status()).isEqualTo(200);
        assertThat(resolved.text("status")).isEqualTo("failed");
        assertThat(resolved.json().path("needs_review").asBoolean()).isFalse();
        assertThat(account("payout_reserved:fakeone")).isEqualTo(reserved - 9000);
        assertThat(get("shop", "/v1/events?resource_id=" + id).json().path("data"))
                .extracting(e -> e.path("type").asString())
                .containsExactlyInAnyOrder("payout.needs_review", "payout.failed");
        assertThat(admin("POST", "/admin/v1/payouts/" + id + "/resolve",
                Map.of("status", "failed", "note", "again")).text("code")).isEqualTo("already_final");
    }

    @Test
    void an_unknown_refund_is_settled_by_the_sweep() {
        String paymentId = post("shop", "/v1/payments", payment("wave")).text("id");
        jdbc.sql("UPDATE payments SET status = 'SUCCEEDED' WHERE id = :id").param("id", paymentId).update();
        FAKE_ONE.script(Behaviour.timeoutAfterAccept());
        Response refund = post("shop", "/v1/payments/" + paymentId + "/refunds", Map.of("amount", 2000));
        assertThat(refund.text("status")).isEqualTo("unknown");
        FAKE_ONE.settleRefund(FAKE_ONE.referenceFor(refund.text("id")), RefundStatus.REFUNDED);
        age("refunds", refund.text("id"), Duration.ofMinutes(2));

        reconciler.sweepRefunds();

        assertThat(get("shop", "/v1/refunds/" + refund.text("id")).text("status")).isEqualTo("refunded");
        assertThat(get("shop", "/v1/payments/" + paymentId).json().path("amount_refunded").asLong()).isEqualTo(2000);
    }

    @Test
    void retention_purges_old_keys_and_callbacks() {
        post("shop", "/v1/payments", payment("wave"));
        jdbc.sql("UPDATE idempotency_keys SET created_at = now() - interval '2 days'").update();
        jdbc.sql("""
                        INSERT INTO inbound_webhooks (application_id, provider, headers, body, received_at, processed_at, result)
                        VALUES (:app, 'fakeone', '{}', '\\x00', now() - interval '100 days', now() - interval '100 days', 'applied')""")
                .param("app", app("shop").id()).update();

        reconciler.purge();

        assertThat(jdbc.sql("SELECT count(*) FROM idempotency_keys WHERE state = 'COMPLETED' AND created_at < now() - interval '1 day'")
                .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM inbound_webhooks WHERE received_at < now() - interval '90 days'")
                .query(Integer.class).single()).isZero();
    }
}
