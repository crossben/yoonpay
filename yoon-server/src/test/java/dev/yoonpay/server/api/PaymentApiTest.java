package dev.yoonpay.server.api;

import dev.yoonpay.testkit.Behaviour;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static dev.yoonpay.server.TestProviders.FAKE_ONE;
import static dev.yoonpay.server.TestProviders.FAKE_TWO;
import static org.assertj.core.api.Assertions.assertThat;

class PaymentApiTest extends ApiTest {

    @Test
    void creates_a_pending_payment_on_the_preferred_provider() {
        Response r = post("shop", "/v1/payments", payment("wave"));

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.text("status")).isEqualTo("pending");
        assertThat(r.text("provider")).isEqualTo("fakeone");
        assertThat(r.text("checkout_url")).startsWith("https://fake.example/checkout/");
        assertThat(r.json().path("amount").asLong()).isEqualTo(5000);
        assertThat(r.json().path("customer").path("phone").asString()).isEqualTo("+22177***67");

        Response fetched = get("shop", "/v1/payments/" + r.text("id"));
        assertThat(fetched.status()).isEqualTo(200);
        assertThat(fetched.text("status")).isEqualTo("pending");

        Response events = get("shop", "/v1/payments/" + r.text("id") + "/events");
        assertThat(events.json().size()).isEqualTo(2);
        assertThat(events.json().get(0).path("to_status").asString()).isEqualTo("created");
        assertThat(events.json().get(1).path("to_status").asString()).isEqualTo("pending");
    }

    @Test
    void the_customer_pi_alias_reaches_the_provider() {
        Response r = post("shop", "/v1/payments", Map.of("amount", 5000, "currency", "XOF", "country", "SN",
                "method", "wave", "customer", Map.of("pi_alias", "c0ffee00-0000-4000-8000-000000000001")));

        assertThat(r.status()).isEqualTo(201);
        assertThat(FAKE_ONE.lastCollect().customerAlias()).isEqualTo("c0ffee00-0000-4000-8000-000000000001");
        assertThat(FAKE_ONE.lastCollect().customerPhone()).isNull();
    }

    @Test
    void a_retry_with_the_same_key_replays_the_original_response_without_calling_the_provider_again() {
        var body = payment("wave");

        Response first = post("shop", "/v1/payments", body, "key-replay-1");
        Response second = post("shop", "/v1/payments", body, "key-replay-1");

        assertThat(second.status()).isEqualTo(201);
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(second.header("Idempotent-Replayed")).contains("true");
        assertThat(FAKE_ONE.mutatingCalls()).isEqualTo(1);
    }

    @Test
    void the_same_key_with_a_different_body_is_refused() {
        post("shop", "/v1/payments", payment("wave"), "key-reuse-1");

        Response r = post("shop", "/v1/payments", payment("wave"), "key-reuse-1");

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.text("code")).isEqualTo("idempotency_key_reused");
    }

    @Test
    void the_idempotency_key_is_required() {
        Response r = send("shop", "POST", "/v1/payments", payment("wave"), null);

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.text("code")).isEqualTo("invalid_request");
    }

    @Test
    void no_provider_for_the_method_is_a_422_with_a_machine_readable_code() {
        Response r = post("shop", "/v1/payments", payment("bitcoin"));

        assertThat(r.status()).isEqualTo(422);
        assertThat(r.text("code")).isEqualTo("no_provider_for_method");
        assertThat(r.json().path("type").asString()).isEqualTo("https://yoonpay.dev/problems/no_provider_for_method");
    }

    @Test
    void a_definite_rejection_fails_over_to_the_next_provider() {
        FAKE_ONE.script(Behaviour.reject("INVALID_WALLET"));

        Response r = post("shop", "/v1/payments", payment("wave"));

        assertThat(r.text("status")).isEqualTo("pending");
        assertThat(r.text("provider")).isEqualTo("faketwo");
        assertThat(r.text("routing_reason")).contains("rejected by fakeone (INVALID_WALLET)");
    }

    @Test
    void a_provider_that_is_down_is_skipped() {
        FAKE_ONE.setDown(true);

        Response r = post("shop", "/v1/payments", payment("wave"));

        assertThat(r.text("provider")).isEqualTo("faketwo");
    }

    @Test
    void a_timeout_after_sending_never_fails_over_so_the_customer_cannot_be_charged_twice() {
        FAKE_ONE.script(Behaviour.timeoutAfterAccept());

        Response r = post("shop", "/v1/payments", payment("wave"));

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.text("status")).isEqualTo("pending");
        assertThat(r.text("provider")).isEqualTo("fakeone");
        assertThat(r.text("routing_reason")).contains("no failover");
        assertThat(FAKE_TWO.mutatingCalls()).isZero();
    }

    @Test
    void a_hanging_provider_is_an_unknown_outcome_too() {
        FAKE_ONE.script(Behaviour.hang(Duration.ofMillis(100)));

        Response r = post("shop", "/v1/payments", payment("wave"));

        assertThat(r.text("status")).isEqualTo("pending");
        assertThat(r.text("provider")).isEqualTo("fakeone");
        assertThat(FAKE_TWO.mutatingCalls()).isZero();
    }

    @Test
    void rejected_everywhere_is_a_failed_payment() {
        FAKE_ONE.script(Behaviour.reject("INSUFFICIENT_FUNDS"));
        FAKE_TWO.script(Behaviour.reject("INSUFFICIENT_FUNDS"));

        Response r = post("shop", "/v1/payments", payment("wave"));

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.text("status")).isEqualTo("failed");
        assertThat(r.json().path("failure").path("code").asString()).isEqualTo("insufficient_funds");
    }

    @Test
    void every_breaker_open_is_all_providers_unavailable() {
        registry.forceState(app("shop"), "fakeone", CircuitBreaker.State.OPEN);
        registry.forceState(app("shop"), "faketwo", CircuitBreaker.State.OPEN);

        Response r = post("shop", "/v1/payments", payment("wave"));

        assertThat(r.status()).isEqualTo(422);
        assertThat(r.text("code")).isEqualTo("all_providers_unavailable");
        assertThat(FAKE_ONE.mutatingCalls()).isZero();
    }

    @Test
    void the_application_can_pin_a_provider() {
        Map<String, Object> body = new HashMap<>(payment("wave"));
        body.put("provider", "faketwo");

        Response r = post("shop", "/v1/payments", body);

        assertThat(r.text("provider")).isEqualTo("faketwo");
        assertThat(r.text("routing_reason")).contains("pinned");
    }

    @Test
    void invalid_input_is_a_400() {
        Map<String, Object> body = new HashMap<>(payment("wave"));
        body.put("customer", Map.of("phone", "12"));

        assertThat(post("shop", "/v1/payments", body).status()).isEqualTo(400);
        assertThat(post("shop", "/v1/payments", Map.of("amount", -5, "currency", "XOF", "country", "SN", "method", "wave")).status())
                .isEqualTo(400);
    }

    @Test
    void requests_without_a_valid_key_are_rejected() {
        assertThat(send(null, "GET", "/v1/payments", null, null).status()).isEqualTo(401);
        Response r = send("bogus", "GET", "/v1/payments", null, null);
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.text("code")).isEqualTo("unauthorized");
    }

    @Test
    void an_application_can_never_see_another_applications_payments() {
        Response mine = post("shop", "/v1/payments", payment("wave"));
        String id = mine.text("id");

        assertThat(get("other", "/v1/payments/" + id).status()).isEqualTo(404);
        assertThat(get("other", "/v1/payments/" + id + "/events").status()).isEqualTo(404);
        assertThat(get("other", "/v1/payments?reference=" + mine.text("reference")).json().path("data").size()).isZero();
        assertThat(post("other", "/v1/payments/" + id + "/refunds", Map.of()).status()).isEqualTo(404);
    }

    @Test
    void search_filters_by_reference_and_phone() {
        Response created = post("shop", "/v1/payments", payment("wave"));

        Response byRef = get("shop", "/v1/payments?reference=" + created.text("reference"));
        assertThat(byRef.json().path("data").size()).isEqualTo(1);
        assertThat(byRef.json().path("data").get(0).path("id").asString()).isEqualTo(created.text("id"));

        Response byPhone = get("shop", "/v1/payments?customer_phone=%2B221771234567&limit=100");
        assertThat(byPhone.json().path("data").size()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void cursor_pagination_is_stable_while_new_payments_arrive() {
        String tag = "page_" + System.nanoTime();
        java.util.List<String> created = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Map<String, Object> body = new HashMap<>(payment("wave"));
            body.put("description", tag);
            body.put("reference", tag);
            created.add(post("shop", "/v1/payments", body).text("id"));
        }

        java.util.List<String> seen = new java.util.ArrayList<>();
        String cursor = null;
        do {
            Response page = get("shop", "/v1/payments?reference=" + tag + "&limit=2"
                    + (cursor == null ? "" : "&starting_after=" + cursor));
            page.json().path("data").forEach(p -> seen.add(p.path("id").asString()));
            cursor = page.json().path("has_more").asBoolean() ? page.text("next_cursor") : null;
            if (seen.size() == 2) {
                Map<String, Object> body = new HashMap<>(payment("wave"));
                body.put("reference", tag); // arrives mid-walk: must not shift the pages
                post("shop", "/v1/payments", body);
            }
        } while (cursor != null);

        assertThat(seen).doesNotHaveDuplicates().containsAll(created);
        assertThat(seen.subList(0, 5)).containsExactlyElementsOf(created.reversed());
    }

    @Test
    void limit_is_bounded() {
        assertThat(get("shop", "/v1/payments?limit=1000").status()).isEqualTo(400);
    }
}
