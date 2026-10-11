package dev.yoonpay.server.api;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Customers, plans and prices (ADR-0025). Every response is validated against the contract. */
class CatalogApiTest extends ApiTest {

    private Response patch(String app, String path, Object body) {
        return send(app, "PATCH", path, body, UUID.randomUUID().toString());
    }

    private static String unique(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ------------------------------------------------------------------ customers

    @Test
    void a_customer_is_created_and_found_by_the_applications_own_id() {
        String external = unique("user");
        Response r = post("shop", "/v1/customers", Map.of("external_id", external, "email", "awa@example.com",
                "phone", "771234567", "country", "SN", "name", "Awa Diop", "metadata", Map.of("plan_source", "web")));

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.text("id")).startsWith("cus_");
        assertThat(r.json().path("phone").asString()).as("masked").isEqualTo("+22177***67");
        assertThat(r.json().path("metadata").path("plan_source").asString()).isEqualTo("web");

        Response found = get("shop", "/v1/customers?external_id=" + external);
        assertThat(found.json().path("data").size()).isEqualTo(1);
        assertThat(found.json().path("data").get(0).path("id").asString()).isEqualTo(r.text("id"));
    }

    @Test
    void the_same_external_id_twice_is_a_conflict_that_names_the_existing_customer() {
        String external = unique("user");
        String first = post("shop", "/v1/customers", Map.of("external_id", external, "country", "SN")).text("id");

        Response again = post("shop", "/v1/customers", Map.of("external_id", external, "country", "CI"));

        assertThat(again.status()).isEqualTo(409);
        assertThat(again.text("code")).isEqualTo("customer_exists");
        assertThat(again.text("detail")).contains(first);
    }

    @Test
    void a_patch_replaces_given_fields_clears_nulls_and_never_changes_the_external_id() {
        String id = post("shop", "/v1/customers", Map.of("external_id", unique("user"), "country", "SN",
                "email", "old@example.com", "name", "Old")).text("id");
        Map<String, Object> body = new HashMap<>();
        body.put("email", "new@example.com");
        body.put("name", null);

        Response r = patch("shop", "/v1/customers/" + id, body);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.text("email")).isEqualTo("new@example.com");
        assertThat(r.json().path("name").isNull()).isTrue();
        assertThat(patch("shop", "/v1/customers/" + id, Map.of("external_id", "x")).status()).isEqualTo(400);
    }

    @Test
    void metadata_is_bounded_and_made_of_strings() {
        Map<String, Object> tooMany = new HashMap<>();
        for (int i = 0; i < 21; i++) {
            tooMany.put("k" + i, "v");
        }
        assertThat(post("shop", "/v1/customers", Map.of("external_id", unique("u"), "country", "SN", "metadata", tooMany)).status())
                .isEqualTo(400);
        assertThat(post("shop", "/v1/customers", Map.of("external_id", unique("u"), "country", "SN",
                "metadata", Map.of("n", 5))).status()).isEqualTo(400);
    }

    @Test
    void customers_are_private_to_their_application() {
        String id = post("shop", "/v1/customers", Map.of("external_id", unique("user"), "country", "SN")).text("id");

        assertThat(get("other", "/v1/customers/" + id).status()).isEqualTo(404);
        assertThat(patch("other", "/v1/customers/" + id, Map.of("name", "x")).status()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ plans and prices

    private Response pro(String slug) {
        return post("shop", "/v1/plans", Map.of("slug", slug, "name", "Pro",
                "features", Map.of("seats", 5, "exports", true),
                "prices", List.of(Map.of("currency", "XOF", "amount", 15000, "interval", "month"),
                        Map.of("currency", "XOF", "amount", 150000, "interval", "year"))));
    }

    @Test
    void a_plan_is_created_with_its_prices_and_features() {
        Response r = pro(unique("pro"));

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.text("id")).startsWith("plan_");
        assertThat(r.json().path("features").path("seats").asInt()).isEqualTo(5);
        assertThat(r.json().path("prices").size()).isEqualTo(2);
        assertThat(r.json().path("prices").get(0).path("interval").asString()).isEqualTo("month");
        assertThat(r.json().path("prices").get(0).path("interval_count").asInt()).isEqualTo(1);
        assertThat(r.json().path("prices").get(1).path("amount").asLong()).isEqualTo(150000);
    }

    @Test
    void a_taken_slug_is_a_conflict_and_nothing_half_created_remains() {
        String slug = unique("pro");
        String first = pro(slug).text("id");

        Response again = pro(slug);

        assertThat(again.status()).isEqualTo(409);
        assertThat(again.text("code")).isEqualTo("plan_exists");
        assertThat(again.text("detail")).contains(first);
        assertThat(jdbc.sql("SELECT count(*) FROM prices WHERE plan_id IN (SELECT id FROM plans WHERE slug = :s)")
                .param("s", slug).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void bad_prices_are_refused() {
        assertThat(post("shop", "/v1/plans", Map.of("slug", unique("p"), "name", "P",
                "prices", List.of(Map.of("currency", "XOF", "amount", 0, "interval", "month")))).status()).isEqualTo(400);
        assertThat(post("shop", "/v1/plans", Map.of("slug", unique("p"), "name", "P",
                "prices", List.of(Map.of("currency", "XOF", "amount", 100, "interval", "fortnight")))).status()).isEqualTo(400);
        assertThat(post("shop", "/v1/plans", Map.of("slug", "Not A Slug", "name", "P")).status()).isEqualTo(400);
    }

    @Test
    void a_new_price_is_added_and_the_old_one_archived_never_edited() {
        Response plan = pro(unique("pro"));
        String planId = plan.text("id");
        String oldPrice = plan.json().path("prices").get(0).path("id").asString();

        Response added = post("shop", "/v1/plans/" + planId + "/prices",
                Map.of("currency", "XOF", "amount", 45000, "interval", "month", "interval_count", 3));
        Response archived = patch("shop", "/v1/prices/" + oldPrice, Map.of("active", false));

        assertThat(added.status()).isEqualTo(201);
        assertThat(added.json().path("interval_count").asInt()).isEqualTo(3);
        assertThat(archived.json().path("active").asBoolean()).isFalse();
        assertThat(archived.json().path("amount").asLong()).isEqualTo(15000);
        assertThat(get("shop", "/v1/plans/" + planId).json().path("prices").size()).as("archived ones stay listed").isEqualTo(3);
    }

    @Test
    void the_database_refuses_to_change_a_price_amount() {
        String priceId = pro(unique("pro")).json().path("prices").get(0).path("id").asString();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbc.sql("UPDATE prices SET amount = 1 WHERE id = :id").param("id", priceId).update())
                .hasMessageContaining("prices are immutable");
    }

    @Test
    void a_plan_is_archived_and_renamed_but_its_slug_never_changes() {
        String id = pro(unique("pro")).text("id");

        Response r = patch("shop", "/v1/plans/" + id, Map.of("active", false, "name", "Pro (legacy)"));

        assertThat(r.json().path("active").asBoolean()).isFalse();
        assertThat(r.text("name")).isEqualTo("Pro (legacy)");
        assertThat(patch("shop", "/v1/plans/" + id, Map.of("slug", "other")).status()).isEqualTo(400);
        assertThat(get("shop", "/v1/plans?active=false").body()).contains(id);
    }

    @Test
    void plans_and_prices_are_private_to_their_application() {
        Response plan = pro(unique("pro"));
        String priceId = plan.json().path("prices").get(0).path("id").asString();

        assertThat(get("other", "/v1/plans/" + plan.text("id")).status()).isEqualTo(404);
        assertThat(get("other", "/v1/prices/" + priceId).status()).isEqualTo(404);
        assertThat(post("other", "/v1/plans/" + plan.text("id") + "/prices",
                Map.of("currency", "XOF", "amount", 1, "interval", "day")).status()).isEqualTo(404);
    }

    @Test
    void a_retried_create_with_the_same_key_creates_one_plan() {
        Map<String, Object> body = Map.of("slug", unique("pro"), "name", "Pro");
        Response a = post("shop", "/v1/plans", body, "plan-key-" + body.get("slug"));
        Response b = post("shop", "/v1/plans", body, "plan-key-" + body.get("slug"));

        assertThat(b.body()).isEqualTo(a.body());
    }
}
