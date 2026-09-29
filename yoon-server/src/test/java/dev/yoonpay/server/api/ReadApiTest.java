package dev.yoonpay.server.api;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReadApiTest extends ApiTest {

    @Test
    void about_is_public_and_points_to_the_source() {
        Response r = send(null, "GET", "/v1/about", null, null);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.text("license")).isEqualTo("AGPL-3.0-only");
        assertThat(r.text("source_url")).startsWith("https://");
    }

    @Test
    void providers_lists_what_this_application_can_use() {
        Response r = get("shop", "/v1/providers");

        assertThat(r.json().size()).isEqualTo(3);
        assertThat(r.json().get(0).path("id").asString()).isEqualTo("fakeone");
        assertThat(r.json().get(0).path("available").asBoolean()).isTrue();
        assertThat(get("other", "/v1/providers").json().size()).isEqualTo(1);
    }

    @Test
    void payments_export_is_csv_with_masked_phones() {
        String reference = "=HYPERLINK(\"x\")";
        Map<String, Object> body = new java.util.HashMap<>(payment("wave"));
        body.put("reference", reference);
        String id = post("shop", "/v1/payments", body).text("id");

        Response csv = get("shop", "/v1/exports/payments.csv");

        assertThat(csv.status()).isEqualTo(200);
        assertThat(csv.header("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("text/csv"));
        assertThat(csv.body()).startsWith("id,created_at,status,amount");
        String line = csv.body().lines().filter(l -> l.startsWith(id)).findFirst().orElseThrow();
        assertThat(line).contains("+22177***67").doesNotContain("771234567");
        assertThat(line).as("formula injection is neutralised").contains("\"'=HYPERLINK(\"\"x\"\")\"");
    }

    @Test
    void other_exports_and_ledger_entries_respond() {
        post("shop", "/v1/payouts", Map.of("amount", 500, "currency", "XOF", "country", "SN", "method", "wave",
                "recipient", Map.of("phone", "+221771234567")));

        assertThat(get("shop", "/v1/exports/refunds.csv").body()).startsWith("id,created_at,payment_id");
        assertThat(get("shop", "/v1/exports/payouts.csv").body()).startsWith("id,created_at,status");
        assertThat(get("shop", "/v1/exports/ledger.csv").body()).startsWith("posting_id,created_at");

        Response entries = get("shop", "/v1/ledger/entries?provider=fakeone&limit=2");
        assertThat(entries.json().path("data").size()).isEqualTo(2);
        assertThat(get("other", "/v1/ledger/entries").json().path("data").size()).isZero();
    }
}
