package dev.yoonpay.server.api;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Coupons (ADR-0029): creation rules, case-insensitive codes, immutable terms. */
class CouponApiTest extends ApiTest {

    private static String code() {
        return "C" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    private Response create(Map<String, Object> body) {
        return post("shop", "/v1/coupons", body);
    }

    private Map<String, Object> percent(String code, int percent) {
        return Map.of("code", code, "percent_off", percent, "duration", "repeating", "duration_in_periods", 3);
    }

    @Test
    void a_percent_coupon_for_three_invoices() {
        String code = code();
        Response r = create(percent(code, 20));

        assertThat(r.status()).as(r.body()).isEqualTo(201);
        assertThat(r.text("id")).startsWith("co_");
        assertThat(r.text("code")).isEqualTo(code);
        assertThat(r.json().path("percent_off").asInt()).isEqualTo(20);
        assertThat(r.json().path("amount_off").isNull()).isTrue();
        assertThat(r.text("duration")).isEqualTo("repeating");
        assertThat(r.json().path("duration_in_periods").asInt()).isEqualTo(3);
        assertThat(r.json().path("times_redeemed").asInt()).isZero();
        assertThat(r.json().path("active").asBoolean()).isTrue();
    }

    @Test
    void an_amount_coupon_in_one_currency_limited_to_a_plan() {
        String plan = post("shop", "/v1/plans", Map.of("slug", "p" + UUID.randomUUID().toString().substring(0, 8),
                "name", "Pro")).text("id");

        Response r = create(Map.of("code", code(), "amount_off", Map.of("currency", "XOF", "amount", 2000),
                "duration", "once", "max_redemptions", 50, "redeem_by", "2030-01-01T00:00:00Z", "plans", List.of(plan)));

        assertThat(r.status()).as(r.body()).isEqualTo(201);
        assertThat(r.json().path("amount_off").path("amount").asLong()).isEqualTo(2000);
        assertThat(r.json().path("amount_off").path("currency").asString()).isEqualTo("XOF");
        assertThat(r.json().path("plans").get(0).asString()).isEqualTo(plan);
        assertThat(r.json().path("max_redemptions").asInt()).isEqualTo(50);
    }

    @Test
    void the_rules_of_a_coupon_are_checked() {
        Map<String, Object> both = new HashMap<>(percent(code(), 10));
        both.put("amount_off", Map.of("currency", "XOF", "amount", 100));

        assertThat(create(both).status()).isEqualTo(400);
        assertThat(create(Map.of("code", code(), "duration", "once")).status()).as("neither").isEqualTo(400);
        assertThat(create(Map.of("code", code(), "percent_off", 10, "duration", "repeating")).status()).isEqualTo(400);
        assertThat(create(Map.of("code", code(), "percent_off", 10, "duration", "once", "duration_in_periods", 2)).status()).isEqualTo(400);
        assertThat(create(Map.of("code", code(), "percent_off", 101, "duration", "once")).status()).isEqualTo(400);
        assertThat(create(Map.of("code", "a b", "percent_off", 10, "duration", "once")).status()).isEqualTo(400);
        assertThat(create(Map.of("code", code(), "amount_off", Map.of("currency", "ZZZ", "amount", 1), "duration", "once")).status()).isEqualTo(400);
        Response noPlan = create(Map.of("code", code(), "percent_off", 10, "duration", "once", "plans", List.of("plan_nope")));
        assertThat(noPlan.status()).isEqualTo(422);
        assertThat(noPlan.text("code")).isEqualTo("plan_not_found");
    }

    @Test
    void codes_are_unique_and_found_whatever_the_case() {
        String code = code();
        String id = create(percent(code, 10)).text("id");

        Response dup = create(percent(code.toLowerCase(), 15));
        Response found = get("shop", "/v1/coupons?code=" + code.toLowerCase());

        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.text("code")).isEqualTo("coupon_exists");
        assertThat(dup.text("detail")).contains(id);
        assertThat(found.json().path("data")).hasSize(1);
        assertThat(found.json().path("data").get(0).path("id").asString()).isEqualTo(id);
    }

    @Test
    void only_active_cap_and_metadata_change_and_the_cap_only_goes_up() {
        String id = create(Map.of("code", code(), "percent_off", 10, "duration", "once", "max_redemptions", 10)).text("id");
        String path = "/v1/coupons/" + id;

        assertThat(send("shop", "PATCH", path, Map.of("percent_off", 50), "k-" + UUID.randomUUID()).status()).isEqualTo(400);
        assertThat(send("shop", "PATCH", path, Map.of("max_redemptions", 5), "k-" + UUID.randomUUID()).status()).isEqualTo(400);

        Response raised = send("shop", "PATCH", path, Map.of("max_redemptions", 20, "active", false,
                "metadata", Map.of("campaign", "rentree")), "k-" + UUID.randomUUID());
        assertThat(raised.status()).as(raised.body()).isEqualTo(200);
        assertThat(raised.json().path("max_redemptions").asInt()).isEqualTo(20);
        assertThat(raised.json().path("active").asBoolean()).isFalse();
        assertThat(raised.json().path("metadata").path("campaign").asString()).isEqualTo("rentree");

        Map<String, Object> uncapped = new HashMap<>();
        uncapped.put("max_redemptions", null);
        Response removed = send("shop", "PATCH", path, uncapped, "k-" + UUID.randomUUID());
        assertThat(removed.json().path("max_redemptions").isNull()).isTrue();
        assertThat(send("shop", "PATCH", path, Map.of("max_redemptions", 3), "k-" + UUID.randomUUID()).status())
                .as("adding a cap back would lower it").isEqualTo(400);
    }

    @Test
    void the_database_refuses_to_change_terms_or_delete_a_coupon() {
        String id = create(percent(code(), 10)).text("id");

        assertThatThrownBy(() -> jdbc.sql("UPDATE coupons SET percent_off = 90 WHERE id = :id").param("id", id).update())
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM coupons WHERE id = :id").param("id", id).update())
                .isNotNull();
        assertThat(get("shop", "/v1/coupons/" + id).json().path("percent_off").asInt()).isEqualTo(10);
    }

    @Test
    void coupons_belong_to_their_application() {
        String id = create(percent(code(), 10)).text("id");

        assertThat(get("other", "/v1/coupons/" + id).status()).isEqualTo(404);
    }
}
