package dev.yoonpay.server.api;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The operator's read-only, per-application views behind the admin token (used by /dashboard). */
class AdminReadApiTest extends ApiTest {

    private String base(String app) {
        return "/admin/v1/applications/" + app(app).id();
    }

    @Test
    void lists_applications() {
        app("shop");
        app("other");
        Response r = admin("GET", "/admin/v1/applications", null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).contains("\"name\":\"shop\"").contains("\"name\":\"other\"");
    }

    @Test
    void shows_one_applications_payments_with_masked_phones_and_their_history() {
        Response p = post("shop", "/v1/payments", payment("wave"));
        String id = p.text("id");

        Response list = admin("GET", base("shop") + "/payments?status=" + p.text("status") + "&limit=100", null);
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.body()).contains(id).contains("+22177***67");
        assertThat(list.body()).doesNotContain("771234567");

        Response history = admin("GET", base("shop") + "/payments/" + id + "/events", null);
        assertThat(history.status()).isEqualTo(200);
        assertThat(history.json().size()).isPositive();
    }

    @Test
    void scopes_every_record_to_the_application_in_the_path() {
        String id = post("shop", "/v1/payments", payment("wave")).text("id");

        assertThat(admin("GET", base("other") + "/payments/" + id + "/events", null).status()).isEqualTo(404);
        assertThat(admin("GET", base("other") + "/payments?limit=100", null).body()).doesNotContain(id);
        assertThat(admin("GET", "/admin/v1/applications/" + UUID.randomUUID() + "/payments", null).status()).isEqualTo(404);
        assertThat(admin("GET", "/admin/v1/applications/not-a-uuid/balances", null).status()).isEqualTo(404);
    }

    @Test
    void shows_refunds_payouts_balances_and_ledger() {
        Response po = post("shop", "/v1/payouts", Map.of("amount", 2500, "currency", "XOF", "country", "SN",
                "method", "wave", "recipient", Map.of("phone", "771234567")));

        assertThat(admin("GET", base("shop") + "/refunds?status=refunded", null).status()).isEqualTo(200);
        Response payouts = admin("GET", base("shop") + "/payouts?limit=100", null);
        assertThat(payouts.body()).contains(po.text("id")).doesNotContain("771234567");
        assertThat(admin("GET", base("shop") + "/payouts/" + po.text("id") + "/events", null).status()).isEqualTo(200);
        assertThat(admin("GET", base("shop") + "/refunds/re_nope/events", null).status()).isEqualTo(404);
        assertThat(admin("GET", base("shop") + "/balances", null).text("object")).isEqualTo("balances");
        assertThat(admin("GET", base("shop") + "/ledger/entries?limit=5", null).status()).isEqualTo(200);
    }

    @Test
    void requires_the_admin_token_not_an_api_key() {
        assertThat(get("shop", base("shop") + "/payments").status()).isEqualTo(401);
        assertThat(send(null, "GET", "/admin/v1/applications", null, null).status()).isEqualTo(401);
    }
}
