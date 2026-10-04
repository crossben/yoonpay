package dev.yoonpay.server.checkout;

import dev.yoonpay.server.api.ApiTest;
import dev.yoonpay.server.settlement.Reconciler;
import dev.yoonpay.testkit.Behaviour;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static dev.yoonpay.server.TestProviders.FAKE_NO_REFUND;
import static dev.yoonpay.server.TestProviders.FAKE_ONE;
import static dev.yoonpay.server.TestProviders.FAKE_TWO;
import static org.assertj.core.api.Assertions.assertThat;

/** Hosted checkout (ADR-0024), end to end over HTTP with the fake providers. */
class CheckoutApiTest extends ApiTest {

    @Autowired
    Reconciler reconciler;

    /** A hosted payment and its page token. */
    record Hosted(String id, String token, String checkoutUrl, Response created) {
    }

    private Hosted hosted(Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(Map.of("amount", 5000, "currency", "XOF", "country", "SN",
                "checkout", "hosted", "description", "Order 1042", "reference", "order_" + UUID.randomUUID(),
                "return_url", "https://shop.example/orders/1042"));
        body.putAll(extra);
        Response r = post("shop", "/v1/payments", body);
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        String url = r.text("checkout_url");
        String token = URI.create(url).getQuery().substring(2);
        return new Hosted(r.text("id"), token, url, r);
    }

    private Hosted hosted() {
        return hosted(Map.of());
    }

    private Response view(String id, String token) {
        return send(null, "GET", "/checkout/api/" + id, null, null,
                token == null ? Map.of() : Map.of(CheckoutController.TOKEN_HEADER, token));
    }

    private Response choose(Hosted h, Map<String, Object> body) {
        return send(null, "POST", "/checkout/api/" + h.id() + "/attempts", body, null,
                Map.of(CheckoutController.TOKEN_HEADER, h.token()));
    }

    private Response choose(Hosted h, String method) {
        return choose(h, Map.of("method", method));
    }

    private int attempts(String paymentId) {
        return jdbc.sql("SELECT count(*) FROM payment_attempts WHERE payment_id = :p").param("p", paymentId)
                .query(Integer.class).single();
    }

    // ------------------------------------------------------------------ creation

    @Test
    void a_hosted_payment_is_created_without_a_method_and_without_calling_any_provider() {
        Hosted h = hosted();

        Response r = h.created();
        assertThat(r.text("status")).isEqualTo("created");
        assertThat(r.text("checkout")).isEqualTo("hosted");
        assertThat(r.json().path("method").asString()).isEqualTo("any");
        assertThat(r.text("provider")).isNull();
        assertThat(r.text("checkout_expires_at")).isNotNull();
        assertThat(h.checkoutUrl()).startsWith("https://yoon.example/checkout/" + h.id() + "?t=");
        assertThat(h.token()).hasSizeGreaterThanOrEqualTo(43);
        assertThat(FAKE_ONE.mutatingCalls() + FAKE_TWO.mutatingCalls() + FAKE_NO_REFUND.mutatingCalls()).isZero();

        Response fetched = get("shop", "/v1/payments/" + h.id());
        assertThat(fetched.text("checkout_url")).isEqualTo(h.checkoutUrl());
        assertThat(fetched.text("checkout")).isEqualTo("hosted");
    }

    @Test
    void direct_payments_say_so_and_still_need_a_method() {
        Response direct = post("shop", "/v1/payments", payment("wave"));
        assertThat(direct.text("checkout")).isEqualTo("direct");
        assertThat(direct.json().path("checkout_expires_at").isNull()).isTrue();

        Response noMethod = post("shop", "/v1/payments", Map.of("amount", 5000, "currency", "XOF", "country", "SN"));
        assertThat(noMethod.status()).isEqualTo(400);
        assertThat(noMethod.text("code")).isEqualTo("invalid_request");

        Response badMode = post("shop", "/v1/payments", Map.of("amount", 5000, "currency", "XOF", "country", "SN",
                "checkout", "popup"));
        assertThat(badMode.status()).isEqualTo(400);
    }

    @Test
    void hosted_cannot_pin_a_provider_and_needs_a_capable_provider() {
        Response pinned = post("shop", "/v1/payments", Map.of("amount", 5000, "currency", "XOF", "country", "SN",
                "checkout", "hosted", "provider", "fakeone"));
        assertThat(pinned.status()).isEqualTo(400);

        Response nowhere = post("shop", "/v1/payments", Map.of("amount", 5000, "currency", "XOF", "country", "CI",
                "checkout", "hosted"));
        assertThat(nowhere.status()).isEqualTo(422);
        assertThat(nowhere.text("code")).isEqualTo("no_provider_for_method");
    }

    // ------------------------------------------------------------------ the page's view

    @Test
    void the_view_lists_the_methods_of_the_configured_providers_and_nothing_secret() {
        Hosted h = hosted(Map.of("customer", Map.of("phone", "+221771234567")));

        Response v = view(h.id(), h.token());

        assertThat(v.status()).isEqualTo(200);
        assertThat(v.text("status")).isEqualTo("created");
        assertThat(v.json().path("amount").asLong()).isEqualTo(5000);
        assertThat(v.text("description")).isEqualTo("Order 1042");
        assertThat(v.json().path("can_choose").asBoolean()).isTrue();
        List<String> methods = new ArrayList<>();
        v.json().path("methods").forEach(m -> methods.add(m.path("method").asString()));
        assertThat(methods).containsExactly("card", "orange_money", "wave");
        assertThat(v.text("customer_phone")).isEqualTo("+22177***67");
        assertThat(v.body()).doesNotContain(h.token()).doesNotContain("+221771234567")
                .doesNotContain(app("shop").id().toString()).doesNotContain("fakeone").doesNotContain("order_");
    }

    @Test
    void a_method_given_at_creation_is_the_only_one_offered() {
        Hosted h = hosted(Map.of("method", "wave"));

        Response v = view(h.id(), h.token());
        assertThat(v.json().path("methods").size()).isEqualTo(1);
        assertThat(v.json().path("methods").get(0).path("method").asString()).isEqualTo("wave");

        Response other = choose(h, "orange_money");
        assertThat(other.status()).isEqualTo(422);
        assertThat(other.text("code")).isEqualTo("method_unavailable");
        assertThat(FAKE_ONE.mutatingCalls()).isZero();
    }

    @Test
    void a_missing_or_wrong_token_or_an_unknown_id_is_the_same_404() {
        Hosted h = hosted();
        Hosted other = hosted();
        Response direct = post("shop", "/v1/payments", payment("wave"));

        List<Response> refused = List.of(
                view(h.id(), null),
                view(h.id(), "wrong"),
                view(h.id(), other.token()),
                view(h.id(), h.token() + "x"),
                view("pay_0000000000000000000000000000dead", h.token()),
                view(direct.text("id"), h.token()),
                send(null, "POST", "/checkout/api/" + h.id() + "/attempts", Map.of("method", "wave"), null,
                        Map.of(CheckoutController.TOKEN_HEADER, other.token())));
        for (Response r : refused) {
            assertThat(r.status()).isEqualTo(404);
            assertThat(r.text("code")).isEqualTo("resource_not_found");
            assertThat(r.text("detail")).isEqualTo("Checkout not found");
        }
        assertThat(FAKE_ONE.mutatingCalls()).as("only the direct payment's call").isEqualTo(1);
        assertThat(attempts(h.id())).isZero();
        assertThat(view(h.id(), h.token()).status()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ choosing

    @Test
    void choosing_a_method_routes_it_and_sends_the_customer_to_the_provider() {
        Hosted h = hosted();

        Response r = choose(h, "wave");

        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(r.text("status")).isEqualTo("pending");
        assertThat(r.json().path("can_choose").asBoolean()).isFalse();
        assertThat(r.json().path("next_action").path("type").asString()).isEqualTo("redirect");
        assertThat(r.json().path("next_action").path("url").asString()).startsWith("https://fake.example/checkout/");
        assertThat(FAKE_ONE.mutatingCalls()).isEqualTo(1);
        // The provider sends the customer back to the Yoon page, which then returns them to the shop.
        assertThat(FAKE_ONE.lastCollect().returnUrl()).hasToString(h.checkoutUrl());
        assertThat(FAKE_ONE.lastCollect().method()).isEqualTo("wave");

        Response p = get("shop", "/v1/payments/" + h.id());
        assertThat(p.text("status")).isEqualTo("pending");
        assertThat(p.text("method")).isEqualTo("wave");
        assertThat(p.text("provider")).isEqualTo("fakeone");
        assertThat(p.text("checkout_url")).isEqualTo(h.checkoutUrl());
    }

    @Test
    void choosing_again_once_a_provider_has_the_payment_is_refused_without_a_second_call() {
        Hosted h = hosted();
        choose(h, "wave");

        Response again = choose(h, "orange_money");

        assertThat(again.status()).isEqualTo(409);
        assertThat(again.text("code")).isEqualTo("checkout_not_open");
        assertThat(FAKE_ONE.mutatingCalls() + FAKE_TWO.mutatingCalls()).isEqualTo(1);
        assertThat(attempts(h.id())).isEqualTo(1);
    }

    @Test
    void a_rejection_fails_over_to_the_next_provider() {
        FAKE_ONE.script(Behaviour.reject("INSUFFICIENT_FUNDS"));
        Hosted h = hosted();

        Response r = choose(h, "wave");

        assertThat(r.text("status")).isEqualTo("pending");
        assertThat(get("shop", "/v1/payments/" + h.id()).text("provider")).isEqualTo("faketwo");
        assertThat(FAKE_TWO.mutatingCalls()).isEqualTo(1);
    }

    @Test
    void when_every_provider_refuses_the_customer_may_choose_another_method() {
        FAKE_ONE.script(Behaviour.reject("DECLINED"));
        FAKE_TWO.script(Behaviour.reject("DECLINED"));
        Hosted h = hosted();

        Response refused = choose(h, "wave");

        assertThat(refused.status()).isEqualTo(200);
        assertThat(refused.text("status")).isEqualTo("created");
        assertThat(refused.json().path("last_error").path("code").asString()).isEqualTo("DECLINED");
        assertThat(refused.json().path("can_choose").asBoolean()).isTrue();
        assertThat(get("shop", "/v1/payments/" + h.id()).text("status")).isEqualTo("created");

        Response second = choose(h, "orange_money");
        assertThat(second.text("status")).isEqualTo("pending");
        assertThat(get("shop", "/v1/payments/" + h.id()).text("method")).isEqualTo("orange_money");
        assertThat(attempts(h.id())).isEqualTo(3);
    }

    @Test
    void the_customer_can_supply_the_phone_or_pi_alias_a_provider_asked_for() {
        FAKE_ONE.script(Behaviour.reject("PI_ALIAS_REQUIRED"));
        FAKE_TWO.script(Behaviour.reject("PI_ALIAS_REQUIRED"));
        Hosted h = hosted();

        Response refused = choose(h, "wave");
        assertThat(refused.json().path("last_error").path("code").asString()).isEqualTo("PI_ALIAS_REQUIRED");

        Response r = choose(h, Map.of("method", "wave", "pi_alias", "c0ffee00-0000-4000-8000-000000000001",
                "phone", "77 123 45 67"));
        assertThat(r.text("status")).isEqualTo("pending");
        assertThat(r.json().path("has_pi_alias").asBoolean()).isTrue();
        assertThat(r.text("customer_phone")).isEqualTo("+22177***67");
        assertThat(FAKE_ONE.lastCollect().customerAlias()).isEqualTo("c0ffee00-0000-4000-8000-000000000001");
        assertThat(FAKE_ONE.lastCollect().customerPhone()).isEqualTo("+221771234567");
    }

    @Test
    void an_invalid_choice_is_a_400_and_starts_nothing() {
        Hosted h = hosted();

        assertThat(choose(h, Map.of("method", "wave", "phone", "12")).status()).isEqualTo(400);
        assertThat(choose(h, Map.of()).status()).isEqualTo(400);
        assertThat(choose(h, "pispi").status()).isEqualTo(422);
        assertThat(attempts(h.id())).isZero();
        assertThat(view(h.id(), h.token()).json().path("can_choose").asBoolean()).isTrue();
    }

    @Test
    void an_unknown_outcome_leaves_the_payment_pending_and_no_second_attempt_is_possible() {
        FAKE_ONE.script(Behaviour.timeoutAfterAccept());
        Hosted h = hosted();

        Response r = choose(h, "wave");

        assertThat(r.text("status")).isEqualTo("pending");
        assertThat(r.json().path("next_action").isNull()).isTrue();
        assertThat(r.json().path("can_choose").asBoolean()).isFalse();
        assertThat(r.json().path("methods").size()).isZero();

        Response again = choose(h, "orange_money");
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.text("code")).isEqualTo("checkout_not_open");
        assertThat(FAKE_TWO.mutatingCalls()).isZero();
        assertThat(attempts(h.id())).isEqualTo(1);
        assertThat(view(h.id(), h.token()).text("status")).isEqualTo("pending");
    }

    @Test
    void a_double_submit_starts_exactly_one_attempt() throws Exception {
        FAKE_ONE.script(Behaviour.hang(Duration.ofMillis(1500)));
        Hosted h = hosted();

        List<Response> answers = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Response>> clicks = List.of(() -> choose(h, "wave"), () -> {
                Thread.sleep(300);
                return choose(h, "wave");
            }, () -> {
                Thread.sleep(400);
                return choose(h, "orange_money");
            });
            for (Future<Response> f : pool.invokeAll(clicks)) {
                answers.add(f.get());
            }
        }

        assertThat(answers).filteredOn(a -> a.status() == 200).hasSize(1);
        assertThat(answers).filteredOn(a -> a.status() == 409)
                .allSatisfy(a -> assertThat(a.text("code")).isIn("checkout_in_progress", "checkout_not_open"))
                .hasSize(2);
        assertThat(FAKE_ONE.mutatingCalls() + FAKE_TWO.mutatingCalls()).isEqualTo(1);
        assertThat(attempts(h.id())).isEqualTo(1);
        // The hang ended as Unknown: pending, never a second provider.
        assertThat(get("shop", "/v1/payments/" + h.id()).text("status")).isEqualTo("pending");
    }

    @Test
    void too_many_refused_attempts_fail_the_payment() {
        Hosted h = hosted();
        for (int round = 0; round < PaymentServiceLimits.ROUNDS_TO_EXHAUST; round++) {
            FAKE_ONE.script(Behaviour.reject("DECLINED"));
            FAKE_TWO.script(Behaviour.reject("DECLINED"));
            assertThat(choose(h, "wave").status()).isEqualTo(200);
        }

        Response p = get("shop", "/v1/payments/" + h.id());
        assertThat(p.text("status")).isEqualTo("failed");
        assertThat(p.json().path("failure").path("code").asString()).isEqualTo("checkout_attempts_exhausted");
        assertThat(choose(h, "wave").text("code")).isEqualTo("checkout_not_open");
    }

    /** 10 attempts allowed, two providers per round. */
    static final class PaymentServiceLimits {
        static final int ROUNDS_TO_EXHAUST = dev.yoonpay.server.payment.PaymentService.MAX_CHECKOUT_ATTEMPTS / 2;
    }

    // ------------------------------------------------------------------ expiry and recovery

    @Test
    void an_expired_checkout_fails_on_read_and_refuses_a_choice() {
        Hosted h = hosted();
        Hosted h2 = hosted();
        jdbc.sql("UPDATE payments SET checkout_expires_at = now() - interval '1 second' WHERE id IN (:ids)")
                .param("ids", List.of(h.id(), h2.id())).update();

        Response chosen = choose(h2, "wave");
        assertThat(chosen.status()).isEqualTo(409);
        assertThat(chosen.text("code")).isEqualTo("checkout_expired");

        Response v = view(h.id(), h.token());
        assertThat(v.text("status")).isEqualTo("failed");
        assertThat(v.json().path("can_choose").asBoolean()).isFalse();
        Response p = get("shop", "/v1/payments/" + h.id());
        assertThat(p.json().path("failure").path("code").asString()).isEqualTo("checkout_expired");
        assertThat(FAKE_ONE.mutatingCalls()).isZero();
        String payload = jdbc.sql("SELECT payload FROM outbound_events WHERE resource_id = :id AND type = 'payment.failed'")
                .param("id", h.id()).query(String.class).single();
        assertThat(payload).contains("checkout_expired");
    }

    @Test
    void the_sweep_expires_unused_checkouts_and_leaves_waiting_ones_alone() {
        Hosted expired = hosted();
        Hosted waiting = hosted();
        jdbc.sql("UPDATE payments SET updated_at = now() - interval '2 minutes', created_at = now() - interval '2 minutes' WHERE id IN (:ids)")
                .param("ids", List.of(expired.id(), waiting.id())).update();
        jdbc.sql("UPDATE payments SET checkout_expires_at = now() - interval '1 second' WHERE id = :id")
                .param("id", expired.id()).update();

        reconciler.sweepPayments();

        assertThat(get("shop", "/v1/payments/" + expired.id()).text("status")).isEqualTo("failed");
        assertThat(get("shop", "/v1/payments/" + waiting.id()).text("status")).isEqualTo("created");
        assertThat(view(waiting.id(), waiting.token()).json().path("can_choose").asBoolean()).isTrue();
    }

    @Test
    void a_round_interrupted_by_a_crash_is_treated_as_an_unknown_outcome() {
        Hosted h = hosted();
        // As if the process died after claiming and sending, before storing the answer.
        jdbc.sql("UPDATE payments SET checkout_busy = true, method = 'wave', updated_at = now() - interval '2 minutes' WHERE id = :id")
                .param("id", h.id()).update();
        jdbc.sql("INSERT INTO payment_attempts (id, payment_id, provider) VALUES (:a, :p, 'fakeone')")
                .param("a", "att_" + UUID.randomUUID().toString().replace("-", "")).param("p", h.id()).update();

        assertThat(choose(h, "wave").text("code")).isEqualTo("checkout_in_progress");
        reconciler.sweepPayments();

        Response p = get("shop", "/v1/payments/" + h.id());
        assertThat(p.text("status")).isEqualTo("pending");
        assertThat(p.text("provider")).isEqualTo("fakeone");
        assertThat(choose(h, "wave").text("code")).isEqualTo("checkout_not_open");
        assertThat(FAKE_ONE.mutatingCalls()).isZero();
    }

    @Test
    void a_claim_left_with_only_refused_attempts_is_released() {
        Hosted h = hosted();
        jdbc.sql("UPDATE payments SET checkout_busy = true, updated_at = now() - interval '2 minutes' WHERE id = :id")
                .param("id", h.id()).update();

        reconciler.sweepPayments();

        assertThat(get("shop", "/v1/payments/" + h.id()).text("status")).isEqualTo("created");
        assertThat(view(h.id(), h.token()).json().path("can_choose").asBoolean()).isTrue();
    }

    // ------------------------------------------------------------------ the page

    @Test
    void the_page_needs_the_token_and_carries_strict_headers() {
        Hosted h = hosted();

        Response page = send(null, "GET", "/checkout/" + h.id() + "?t=" + h.token(), null, null);
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.header("Content-Type").orElseThrow()).startsWith("text/html");
        assertThat(page.body()).contains("/checkout/checkout.js").contains("/checkout/checkout.css")
                .doesNotContain(h.token()).doesNotContain("<script>").doesNotContain("style=");
        assertStrictHeaders(page);

        assertThat(send(null, "GET", "/checkout/" + h.id() + "?t=nope", null, null).status()).isEqualTo(404);
        assertThat(send(null, "GET", "/checkout/" + h.id(), null, null).status()).isEqualTo(404);

        for (String asset : new String[]{"/checkout/checkout.js", "/checkout/checkout.css"}) {
            Response r = send(null, "GET", asset, null, null);
            assertThat(r.status()).as(asset).isEqualTo(200);
            assertStrictHeaders(r);
        }
        assertStrictHeaders(view(h.id(), h.token()));
        assertStrictHeaders(view(h.id(), "wrong"));
    }

    private static void assertStrictHeaders(Response r) {
        assertThat(r.header("Content-Security-Policy")).contains(CheckoutFilter.CSP);
        assertThat(CheckoutFilter.CSP).doesNotContain("unsafe").doesNotContain("http").contains("default-src 'none'")
                .contains("frame-ancestors 'none'").contains("form-action 'none'");
        assertThat(r.header("X-Content-Type-Options")).contains("nosniff");
        assertThat(r.header("X-Frame-Options")).contains("DENY");
        assertThat(r.header("Referrer-Policy")).contains("no-referrer");
        assertThat(r.header("Cache-Control")).contains("no-store");
    }
}
