package dev.yoonpay.server.api;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Payment links (ADR-0030): creation rules, fixed amounts, archive-only changes. */
class PaymentLinkApiTest extends ApiTest {

    private Response create(Map<String, Object> body) {
        return post("shop", "/v1/payment_links", body);
    }

    private static Map<String, Object> fixed() {
        return new HashMap<>(Map.of("amount", 5000, "currency", "XOF", "country", "SN", "description", "Tote bag"));
    }

    @Test
    void a_fixed_amount_link_has_a_shareable_url_and_a_qr_code() {
        Map<String, Object> body = fixed();
        body.put("max_payments", 1);
        Response r = create(body);

        assertThat(r.status()).as(r.body()).isEqualTo(201);
        assertThat(r.text("id")).startsWith("plink_");
        assertThat(r.text("url")).matches("https?://[^/]+/l/[A-Za-z0-9_-]{22}");
        assertThat(r.text("qr_url")).isEqualTo(r.text("url") + "/qr.svg");
        assertThat(r.json().path("amount").asLong()).isEqualTo(5000);
        assertThat(r.json().path("max_payments").asInt()).isEqualTo(1);
        assertThat(r.json().path("payments_count").asLong()).isZero();
        assertThat(r.json().path("amount_collected").asLong()).isZero();
        assertThat(r.json().path("active").asBoolean()).isTrue();
    }

    @Test
    void a_link_can_let_the_customer_choose_the_amount_within_bounds() {
        Response r = create(Map.of("currency", "XOF", "country", "SN", "description", "Donation",
                "min_amount", 500, "max_amount", 100000));

        assertThat(r.status()).as(r.body()).isEqualTo(201);
        assertThat(r.json().path("amount").isNull()).isTrue();
        assertThat(r.json().path("min_amount").asLong()).isEqualTo(500);
        assertThat(r.json().path("max_amount").asLong()).isEqualTo(100000);
    }

    @Test
    void the_rules_of_a_link_are_checked() {
        Map<String, Object> both = fixed();
        both.put("min_amount", 100);
        Map<String, Object> past = fixed();
        past.put("expires_at", "2020-01-01T00:00:00Z");
        Map<String, Object> badUrl = fixed();
        badUrl.put("return_url", "javascript:alert(1)");

        assertThat(create(both).status()).as("fixed amount with bounds").isEqualTo(400);
        assertThat(create(Map.of("currency", "XOF", "country", "SN", "description", "x", "min_amount", 900, "max_amount", 100)).status()).isEqualTo(400);
        assertThat(create(past).status()).isEqualTo(400);
        assertThat(create(badUrl).status()).isEqualTo(400);
        assertThat(create(Map.of("amount", 5000, "currency", "ZZZ", "country", "SN", "description", "x")).status()).isEqualTo(400);
        assertThat(create(Map.of("amount", 5000, "currency", "XOF", "country", "SN", "description", " ")).status()).isEqualTo(400);
        assertThat(create(Map.of("amount", 0, "currency", "XOF", "country", "SN", "description", "x")).status()).isEqualTo(400);
    }

    @Test
    void only_the_description_dates_cap_state_and_metadata_change() {
        String id = create(fixed()).text("id");
        String path = "/v1/payment_links/" + id;

        assertThat(send("shop", "PATCH", path, Map.of("amount", 1), "k-" + UUID.randomUUID()).status()).isEqualTo(400);

        Response r = send("shop", "PATCH", path, Map.of("active", false, "description", "Tote bag (blue)",
                "max_payments", 10, "metadata", Map.of("campaign", "tabaski")), "k-" + UUID.randomUUID());
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(r.json().path("active").asBoolean()).isFalse();
        assertThat(r.text("description")).isEqualTo("Tote bag (blue)");
        assertThat(r.json().path("max_payments").asInt()).isEqualTo(10);
        assertThat(get("shop", "/v1/payment_links?active=false").body()).contains(id);
        assertThat(get("shop", "/v1/payment_links?active=true").body()).doesNotContain(id);
    }

    @Test
    void the_database_refuses_to_change_an_amount_or_delete_a_link() {
        String id = create(fixed()).text("id");

        assertThatThrownBy(() -> jdbc.sql("UPDATE payment_links SET amount = 1 WHERE id = :id").param("id", id).update())
                .hasMessageContaining("never change");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM payment_links WHERE id = :id").param("id", id).update())
                .isNotNull();
    }

    @Test
    void links_belong_to_their_application() {
        String id = create(fixed()).text("id");

        assertThat(get("other", "/v1/payment_links/" + id).status()).isEqualTo(404);
    }

    @Test
    void an_ordinary_payment_has_no_payment_link() {
        Response p = post("shop", "/v1/payments", payment("wave"));

        assertThat(p.status()).as(p.body()).isIn(200, 201);
        assertThat(p.json().path("payment_link").isNull()).isTrue();
    }
}
