package dev.yoonpay.server.api;

import dev.yoonpay.core.lifecycle.PayoutStatus;
import dev.yoonpay.core.lifecycle.RefundStatus;
import dev.yoonpay.core.money.Money;
import dev.yoonpay.core.provider.PayoutRequest;
import dev.yoonpay.server.lifecycle.Alerts;
import dev.yoonpay.server.settlement.Reconciler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Currency;
import java.util.Map;

import static dev.yoonpay.server.TestProviders.FAKE_ONE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Money-safety plan 2026-11, H2: a refund or payout the process stopped around (left CREATED) is
 * recovered as an unknown outcome — never sent again — then found by Yoon's id or settled by a human.
 */
class InterruptedRecoveryTest extends ApiTest {

    @Autowired
    Reconciler reconciler;

    @Autowired
    Alerts alerts;

    private String succeededPayment() {
        String id = post("shop", "/v1/payments", payment("wave")).text("id");
        jdbc.sql("UPDATE payments SET status = 'SUCCEEDED' WHERE id = :id").param("id", id).update();
        return id;
    }

    /** As if the process died after the provider took the call, before Yoon recorded the answer. */
    private void interrupt(String table, String id, String age) {
        jdbc.sql("UPDATE " + table + " SET status = 'CREATED', provider_reference = NULL, "
                        + "created_at = now() - CAST(:age AS interval), updated_at = now() - CAST(:age AS interval) WHERE id = :id")
                .param("age", age).param("id", id).update();
    }

    private void age(String table, String id) {
        jdbc.sql("UPDATE " + table + " SET updated_at = now() - interval '10 minutes' WHERE id = :id").param("id", id).update();
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
    void an_interrupted_refund_becomes_unknown_is_found_by_its_id_and_settles_once() {
        String payment = succeededPayment();
        Response r = post("shop", "/v1/payments/" + payment + "/refunds", Map.of("amount", 2000));
        String id = r.text("id");
        interrupt("refunds", id, "10 minutes");
        int sent = FAKE_ONE.mutatingCalls();

        reconciler.sweepRefunds();
        assertThat(get("shop", "/v1/refunds/" + id).text("status")).isEqualTo("unknown");

        FAKE_ONE.settleRefund(FAKE_ONE.referenceFor(id), RefundStatus.REFUNDED);
        age("refunds", id);
        reconciler.sweepRefunds();

        Response after = get("shop", "/v1/refunds/" + id);
        assertThat(after.text("status")).isEqualTo("refunded");
        assertThat(after.text("provider_reference")).isEqualTo(FAKE_ONE.referenceFor(id).value());
        assertThat(FAKE_ONE.mutatingCalls()).as("never sent again").isEqualTo(sent);
        assertThat(get("shop", "/v1/refunds/" + id + "/events").body()).contains("creation interrupted");
    }

    @Test
    void an_interrupted_payout_is_reserved_at_once_then_paid_from_the_reservation() {
        // The process died after the provider took the payout, before Yoon recorded anything else:
        // the row is still CREATED, no reservation was posted, the provider has the payout.
        String id = "po_interrupted" + System.nanoTime();
        jdbc.sql("""
                        INSERT INTO payouts (id, application_id, status, amount, currency, country, method, recipient_phone,
                                             provider, created_at, updated_at)
                        VALUES (:id, :app, 'CREATED', 6000, 'XOF', 'SN', 'wave', '+221771234567', 'fakeone',
                                now() - interval '10 minutes', now() - interval '10 minutes')""")
                .param("id", id).param("app", app("shop").id()).update();
        FAKE_ONE.payout(new PayoutRequest(id, new Money(6000, Currency.getInstance("XOF")), "SN", "wave",
                "+221771234567", null, null));
        long reserved = account("payout_reserved:fakeone");
        int sent = FAKE_ONE.mutatingCalls();

        reconciler.sweepPayouts();
        assertThat(get("shop", "/v1/payouts/" + id).text("status")).isEqualTo("unknown");
        assertThat(account("payout_reserved:fakeone")).as("money may have left: reserved").isEqualTo(reserved + 6000);

        FAKE_ONE.settlePayout(FAKE_ONE.referenceFor(id), PayoutStatus.PAID);
        age("payouts", id);
        reconciler.sweepPayouts();

        assertThat(get("shop", "/v1/payouts/" + id).text("status")).isEqualTo("paid");
        assertThat(account("payout_reserved:fakeone")).isEqualTo(reserved);
        assertThat(FAKE_ONE.mutatingCalls()).as("never sent again").isEqualTo(sent);
    }

    @Test
    void a_refund_still_being_created_is_left_alone() {
        String payment = succeededPayment();
        String id = post("shop", "/v1/payments/" + payment + "/refunds", Map.of("amount", 1000)).text("id");
        interrupt("refunds", id, "1 minute");

        reconciler.sweepRefunds();

        assertThat(get("shop", "/v1/refunds/" + id).text("status")).isEqualTo("created");
    }

    @Test
    void a_refund_the_provider_cannot_find_is_flagged_once_and_a_human_settles_it() {
        String payment = succeededPayment();
        String id = "re_interrupted" + System.nanoTime();
        jdbc.sql("""
                        INSERT INTO refunds (id, application_id, payment_id, status, amount, currency, provider, created_at, updated_at)
                        VALUES (:id, :app, :p, 'CREATED', 5000, 'XOF', 'fakeone', now() - interval '25 hours', now() - interval '25 hours')""")
                .param("id", id).param("app", app("shop").id()).param("p", payment).update();
        double before = alerts.count(Alerts.Type.refund_needs_review);

        reconciler.sweepRefunds();
        age("refunds", id);
        reconciler.sweepRefunds();
        age("refunds", id);
        reconciler.sweepRefunds();

        Response flagged = get("shop", "/v1/refunds/" + id);
        assertThat(flagged.text("status")).isEqualTo("unknown");
        assertThat(flagged.json().path("needs_review").asBoolean()).isTrue();
        assertThat(alerts.count(Alerts.Type.refund_needs_review)).as("once").isEqualTo(before + 1);
        assertThat(post("shop", "/v1/payments/" + payment + "/refunds", Map.of("amount", 5000)).status())
                .as("an unknown refund still holds its amount").isEqualTo(422);

        Response resolved = admin("POST", "/admin/v1/refunds/" + id + "/resolve",
                Map.of("status", "failed", "note", "Not on the provider dashboard"));
        assertThat(resolved.text("status")).isEqualTo("failed");
        assertThat(resolved.json().path("holds_amount").asBoolean()).as("the provider never saw it").isFalse();
        assertThat(post("shop", "/v1/payments/" + payment + "/refunds", Map.of("amount", 5000)).status()).isEqualTo(201);
    }
}
