package dev.yoonpay.server.api;

import dev.yoonpay.server.checkout.CheckoutController;
import dev.yoonpay.server.checkout.CheckoutFilter;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The customer portal page (ADR-0031): one customer's subscriptions and invoices, nothing else. */
class PortalPageTest extends BillingTestSupport {

    private record Session(String id, String token, String url) {
    }

    private Session session(String customerId, Map<String, Object> body) {
        Response r = post("shop", "/v1/customers/" + customerId + "/portal_sessions", body);
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        URI u = URI.create(r.text("url"));
        String path = u.getPath();
        return new Session(path.substring(path.lastIndexOf('/') + 1), u.getQuery().substring(2), r.text("url"));
    }

    private Response view(Session s) {
        return send(null, "GET", "/checkout/api/portal/" + s.id(), null, null, Map.of(CheckoutController.TOKEN_HEADER, s.token()));
    }

    @Test
    void the_page_is_served_with_the_checkout_rules() {
        Session s = session(customer, Map.of());
        URI u = URI.create(s.url());

        Response page = send(null, "GET", u.getRawPath() + "?" + u.getRawQuery(), null, null, Map.of());

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body()).contains("portal.js");
        assertThat(page.headers().get("Content-Security-Policy")).containsExactly(CheckoutFilter.CSP);
        assertThat(page.headers().get("Cache-Control")).containsExactly("no-store");
    }

    @Test
    void the_portal_shows_the_customers_subscriptions_invoices_and_receipts() {
        String paid = subscribe(monthly).text("id");
        payInvoice(sub(paid).json().path("latest_invoice"));
        String unpaid = subscribe(yearly).text("id");

        JsonNode v = view(session(customer, Map.of("return_url", "https://shop.example/account"))).json();

        assertThat(v.path("return_url").asString()).isEqualTo("https://shop.example/account");
        assertThat(v.path("subscriptions")).hasSize(2);
        JsonNode active = find(v.path("subscriptions"), paid);
        assertThat(active.path("status").asString()).isEqualTo("active");
        assertThat(active.path("entitled").asBoolean()).isTrue();
        assertThat(active.path("plan").asString()).isEqualTo("Pro");
        assertThat(active.path("open_invoice").isNull()).isTrue();
        JsonNode open = find(v.path("subscriptions"), unpaid).path("open_invoice");
        assertThat(open.path("amount_due").asLong()).isEqualTo(150000);
        assertThat(open.path("checkout_url").asString()).contains("/checkout/pay_");
        assertThat(v.path("invoices")).hasSize(2);
        assertThat(v.path("invoices").toString()).contains("/checkout/receipts/in_");
        // the merchant's private data stays out
        assertThat(v.toString()).doesNotContain("external_id").doesNotContain("metadata").doesNotContain("user_");
    }

    @Test
    void another_customers_data_never_shows() {
        subscribe(monthly);
        String other = post("shop", "/v1/customers", Map.of("external_id", "u_" + UUID.randomUUID(), "country", "SN")).text("id");

        JsonNode v = view(session(other, Map.of())).json();

        assertThat(v.path("subscriptions")).isEmpty();
        assertThat(v.path("invoices")).isEmpty();
    }

    @Test
    void the_actions_the_merchant_allowed_are_listed() {
        JsonNode allow = view(session(customer, Map.of("allow",
                Map.of("cancel", true, "change_prices", List.of(premium))))).json().path("allow");

        assertThat(allow.path("cancel").asBoolean()).isTrue();
        assertThat(allow.path("coupons").asBoolean()).isFalse();
        assertThat(allow.path("prices")).hasSize(1);
        assertThat(allow.path("prices").get(0).path("plan").asString()).isEqualTo("Max");
    }

    @Test
    void wrong_unknown_and_expired_sessions_are_the_same_404() {
        Session s = session(customer, Map.of());
        Session expired = session(customer, Map.of());
        jdbc.sql("UPDATE portal_sessions SET expires_at = now() - interval '1 second' WHERE id = :id").param("id", expired.id()).update();

        for (Response r : List.of(
                view(new Session(s.id(), "wrong", s.url())),
                view(new Session("ps_nope", s.token(), s.url())),
                view(expired),
                send(null, "GET", "/checkout/api/portal/" + s.id(), null, null, Map.of()))) {
            assertThat(r.status()).isEqualTo(404);
            assertThat(r.text("code")).isEqualTo("resource_not_found");
        }
        assertThat(send(null, "GET", "/checkout/portal/" + s.id() + "?t=wrong", null, null, Map.of()).status()).isEqualTo(404);
    }

    private static JsonNode find(JsonNode list, String id) {
        for (JsonNode n : list) {
            if (n.path("id").asString().equals(id)) {
                return n;
            }
        }
        throw new AssertionError("no " + id);
    }
}
