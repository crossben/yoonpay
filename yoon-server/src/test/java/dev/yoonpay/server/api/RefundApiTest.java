package dev.yoonpay.server.api;

import dev.yoonpay.testkit.Behaviour;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static dev.yoonpay.server.TestProviders.FAKE_ONE;
import static dev.yoonpay.server.TestProviders.FAKE_TWO;
import static org.assertj.core.api.Assertions.assertThat;

class RefundApiTest extends ApiTest {

    /** A payment the provider has confirmed. Settlement arrives with webhooks; here it is set directly. */
    private String succeededPayment(String method) {
        String id = post("shop", "/v1/payments", payment(method)).text("id");
        jdbc.sql("UPDATE payments SET status = 'SUCCEEDED' WHERE id = :id").param("id", id).update();
        return id;
    }

    @Test
    void partial_refunds_are_allowed_up_to_the_payment_amount() {
        String id = succeededPayment("wave");

        Response first = post("shop", "/v1/payments/" + id + "/refunds", Map.of("amount", 3000, "reason", "damaged"));
        Response second = post("shop", "/v1/payments/" + id + "/refunds", Map.of("amount", 2000));
        Response third = post("shop", "/v1/payments/" + id + "/refunds", Map.of("amount", 1));

        assertThat(first.status()).isEqualTo(201);
        assertThat(first.text("status")).isEqualTo("pending");
        assertThat(first.text("provider")).isEqualTo("fakeone");
        assertThat(second.status()).isEqualTo(201);
        assertThat(third.status()).isEqualTo(422);
        assertThat(third.text("code")).isEqualTo("refund_exceeds_payment");
        assertThat(get("shop", "/v1/payments/" + id + "/refunds").json().size()).isEqualTo(2);
    }

    @Test
    void omitting_the_amount_refunds_what_is_left() {
        String id = succeededPayment("wave");
        post("shop", "/v1/payments/" + id + "/refunds", Map.of("amount", 1500));

        Response rest = post("shop", "/v1/payments/" + id + "/refunds", Map.of());

        assertThat(rest.json().path("amount").asLong()).isEqualTo(3500);
    }

    @Test
    void concurrent_refunds_can_never_exceed_what_was_paid() throws Exception {
        String id = succeededPayment("wave");
        int n = 10;
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();

        try (var pool = Executors.newFixedThreadPool(n)) {
            for (int i = 0; i < n; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return post("shop", "/v1/payments/" + id + "/refunds", Map.of("amount", 1000)).status();
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get());
            }
            assertThat(statuses).filteredOn(s -> s == 201).hasSize(5);
            assertThat(statuses).filteredOn(s -> s == 422).hasSize(5);
        }
        long refunded = jdbc.sql("SELECT sum(amount) FROM refunds WHERE payment_id = :id").param("id", id)
                .query(Long.class).single();
        assertThat(refunded).isEqualTo(5000);
    }

    @Test
    void only_succeeded_payments_can_be_refunded() {
        String id = post("shop", "/v1/payments", payment("wave")).text("id");

        Response r = post("shop", "/v1/payments/" + id + "/refunds", Map.of());

        assertThat(r.status()).isEqualTo(422);
        assertThat(r.text("code")).isEqualTo("payment_not_refundable");
    }

    @Test
    void a_provider_without_a_refund_api_is_refused_before_any_call() {
        String id = succeededPayment("card");

        Response r = post("shop", "/v1/payments/" + id + "/refunds", Map.of());

        assertThat(r.status()).isEqualTo(422);
        assertThat(r.text("code")).isEqualTo("refund_not_supported");
    }

    @Test
    void a_refund_whose_answer_is_lost_is_unknown_and_never_sent_elsewhere() {
        String id = succeededPayment("wave");
        int before = FAKE_ONE.mutatingCalls();
        FAKE_ONE.script(Behaviour.timeoutAfterAccept());

        Response r = post("shop", "/v1/payments/" + id + "/refunds", Map.of("amount", 1000));

        assertThat(r.text("status")).isEqualTo("unknown");
        assertThat(FAKE_ONE.mutatingCalls()).isEqualTo(before + 1);
        assertThat(FAKE_TWO.mutatingCalls()).isZero();
    }

    @Test
    void a_rejected_refund_is_failed_and_frees_the_amount_again() {
        String id = succeededPayment("wave");
        FAKE_ONE.script(Behaviour.reject("REFUND_WINDOW_CLOSED"));

        Response failed = post("shop", "/v1/payments/" + id + "/refunds", Map.of("amount", 5000));
        Response retry = post("shop", "/v1/payments/" + id + "/refunds", Map.of("amount", 5000));

        assertThat(failed.text("status")).isEqualTo("failed");
        assertThat(failed.json().path("failure").path("code").asString()).isEqualTo("refund_window_closed");
        assertThat(retry.status()).isEqualTo(201);
    }

    @Test
    void refunds_are_listed_fetched_and_have_a_history() {
        String id = succeededPayment("wave");
        String refundId = post("shop", "/v1/payments/" + id + "/refunds", Map.of("amount", 100)).text("id");

        assertThat(get("shop", "/v1/refunds/" + refundId).text("payment_id")).isEqualTo(id);
        assertThat(get("shop", "/v1/refunds?limit=5").json().path("data").size()).isGreaterThanOrEqualTo(1);
        assertThat(get("shop", "/v1/refunds/" + refundId + "/events").json().size()).isEqualTo(2);
        assertThat(get("other", "/v1/refunds/" + refundId).status()).isEqualTo(404);
    }

    @Test
    void a_refund_retry_with_the_same_key_is_replayed_not_repeated() {
        String id = succeededPayment("wave");
        Map<String, Object> body = new HashMap<>(Map.of("amount", 1000));

        Response a = post("shop", "/v1/payments/" + id + "/refunds", body, "refund-key-1");
        Response b = post("shop", "/v1/payments/" + id + "/refunds", body, "refund-key-1");

        assertThat(b.body()).isEqualTo(a.body());
        assertThat(jdbc.sql("SELECT count(*) FROM refunds WHERE payment_id = :id").param("id", id)
                .query(Integer.class).single()).isEqualTo(1);
    }
}
