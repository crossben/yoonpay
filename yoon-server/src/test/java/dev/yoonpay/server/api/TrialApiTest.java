package dev.yoonpay.server.api;

import dev.yoonpay.server.billing.BillingSweep;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Free trials (ADR-0028). Time moves by ageing rows in SQL. */
class TrialApiTest extends BillingTestSupport {

    @Autowired
    BillingSweep sweep;

    private String planOf(String price) {
        return get("shop", "/v1/prices/" + price).text("plan");
    }

    private void planTrial(String price, int days) {
        Response r = send("shop", "PATCH", "/v1/plans/" + planOf(price), Map.of("trial_days", days), "k-" + UUID.randomUUID());
        assertThat(r.status()).as(r.body()).isEqualTo(200);
    }

    private Response subscribeWith(Map<String, Object> extra) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("customer", customer, "price", monthly,
                "return_url", "https://shop.example/billing"));
        body.putAll(extra);
        return post("shop", "/v1/subscriptions", body);
    }

    /** Moves the trial's end `days` from now (negative: in the past). */
    private void trialEndsIn(String id, int days) {
        jdbc.sql("""
                        UPDATE subscriptions
                        SET current_period_start = now() + make_interval(days => :d - 14),
                            current_period_end = now() + make_interval(days => :d),
                            trial_end = now() + make_interval(days => :d)
                        WHERE id = :id""")
                .param("d", days).param("id", id).update();
    }

    @Test
    void the_plans_trial_starts_entitled_with_nothing_to_pay() {
        planTrial(monthly, 14);

        Response r = subscribeWith(Map.of());

        assertThat(r.status()).as(r.body()).isEqualTo(201);
        assertThat(r.text("status")).isEqualTo("trialing");
        assertThat(r.json().path("entitled").asBoolean()).isTrue();
        assertThat(r.json().path("latest_invoice").isNull()).isTrue();
        Duration trial = Duration.between(Instant.now(), at(r, "trial_end"));
        assertThat(trial.toHours()).isBetween(14 * 24L - 1, 14 * 24L);
        assertThat(at(r, "current_period_end")).isEqualTo(at(r, "trial_end"));
        assertThat(eventTypes(r.text("id"))).containsExactly("subscription.created");
        assertThat(get("shop", "/v1/plans/" + planOf(monthly)).json().path("trial_days").asInt()).isEqualTo(14);
    }

    @Test
    void trial_days_zero_skips_the_plans_trial() {
        planTrial(monthly, 14);

        Response r = subscribeWith(Map.of("trial_days", 0));

        assertThat(r.text("status")).isEqualTo("incomplete");
        assertThat(r.json().path("trial_end").isNull()).isTrue();
        assertThat(r.json().path("latest_invoice").path("kind").asString()).isEqualTo("first");
    }

    @Test
    void one_trial_per_customer_and_plan() {
        subscribeWith(Map.of("trial_days", 7));

        Response asked = subscribeWith(Map.of("trial_days", 7));
        planTrial(monthly, 7);
        Response byDefault = subscribeWith(Map.of());

        assertThat(asked.status()).isEqualTo(409);
        assertThat(asked.text("code")).isEqualTo("trial_already_used");
        assertThat(byDefault.status()).as(byDefault.body()).isEqualTo(201);
        assertThat(byDefault.text("status")).as("the plan's default is for first-timers").isEqualTo("incomplete");
    }

    @Test
    void paying_during_the_trial_keeps_the_trial_days() {
        String id = subscribeWith(Map.of("trial_days", 10)).text("id");
        Instant trialEnd = at(sub(id), "trial_end");

        Response invoice = renew(id);
        assertThat(invoice.text("kind")).isEqualTo("first");
        payInvoice(invoice.json());

        Response s = sub(id);
        assertThat(s.text("status")).isEqualTo("active");
        assertThat(at(s, "current_period_start")).isEqualTo(trialEnd);
        assertThat(eventTypes(id)).contains("subscription.activated");
    }

    @Test
    void an_unpaid_trial_goes_to_grace_then_expires() {
        String id = subscribeWith(Map.of("trial_days", 10)).text("id");
        trialEndsIn(id, -1);

        sweep.endPeriods();
        Response pastDue = sub(id);
        assertThat(pastDue.text("status")).isEqualTo("past_due");
        assertThat(pastDue.json().path("entitled").asBoolean()).isTrue();
        assertThat(pastDue.json().path("latest_invoice").path("kind").asString()).isEqualTo("first");

        trialEndsIn(id, -5);  // past the default 3 grace days
        sweep.endGrace();
        assertThat(sub(id).text("status")).isEqualTo("expired");
        assertThat(eventTypes(id)).containsSubsequence("subscription.past_due", "subscription.expired");
    }

    @Test
    void the_trial_end_is_announced_once() {
        String id = subscribeWith(Map.of("trial_days", 10)).text("id");
        trialEndsIn(id, 2);

        sweep.remind();
        sweep.remind();

        assertThat(eventTypes(id).stream().filter("subscription.trial_will_end"::equals)).hasSize(1);
        assertThat(eventTypes(id)).doesNotContain("subscription.renewal_upcoming");
    }

    @Test
    void canceling_at_the_trial_end_cancels_when_it_ends() {
        String id = subscribeWith(Map.of("trial_days", 10)).text("id");
        post("shop", "/v1/subscriptions/" + id + "/cancel", Map.of());
        assertThat(sub(id).json().path("cancel_at_period_end").asBoolean()).isTrue();
        trialEndsIn(id, -1);

        sweep.endPeriods();

        assertThat(sub(id).text("status")).isEqualTo("canceled");
        assertThat(sub(id).json().path("latest_invoice").isNull()).as("nothing to pay").isTrue();
    }

    @Test
    void a_plan_change_during_the_trial_only_switches_the_price() {
        String id = subscribeWith(Map.of("trial_days", 10)).text("id");

        Response r = post("shop", "/v1/subscriptions/" + id + "/change", Map.of("price", premium));

        assertThat(r.status()).as(r.body()).isEqualTo(200);
        Response s = sub(id);
        assertThat(s.text("status")).isEqualTo("trialing");
        assertThat(s.json().path("price").path("id").asString()).isEqualTo(premium);
        assertThat(s.json().path("latest_invoice").isNull()).isTrue();
        assertThat(eventTypes(id)).endsWith("subscription.changed");
    }

    @Test
    void trial_days_are_checked() {
        assertThat(subscribeWith(Map.of("trial_days", 91)).status()).isEqualTo(400);
        assertThat(send("shop", "PATCH", "/v1/plans/" + planOf(monthly), Map.of("trial_days", -1), "k-" + UUID.randomUUID())
                .status()).isEqualTo(400);
    }
}
