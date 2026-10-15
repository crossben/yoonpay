package dev.yoonpay.server.api;

import dev.yoonpay.server.portal.PortalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Customer portal sessions (ADR-0031): one customer, short-lived, only the allowed actions. */
class PortalSessionTest extends BillingTestSupport {

    @Autowired
    PortalService portal;

    private Response session(String customerId, Map<String, Object> body) {
        return post("shop", "/v1/customers/" + customerId + "/portal_sessions", body);
    }

    private static String[] idAndToken(String url) {
        URI u = URI.create(url);
        String path = u.getPath();
        return new String[] {path.substring(path.lastIndexOf('/') + 1), u.getQuery().substring(2)};
    }

    @Test
    void a_session_is_a_short_lived_url_for_one_customer() {
        Response r = session(customer, Map.of("return_url", "https://shop.example/account"));

        assertThat(r.status()).as(r.body()).isEqualTo(201);
        assertThat(r.text("id")).startsWith("ps_");
        assertThat(r.text("customer")).isEqualTo(customer);
        assertThat(r.text("url")).contains("/checkout/portal/" + r.text("id") + "?t=");
        Duration ttl = Duration.between(Instant.now(), at(r, "expires_at"));
        assertThat(ttl.toMinutes()).isBetween(58L, 60L);
    }

    @Test
    void only_the_token_hash_is_stored_and_the_token_opens_the_session() {
        String[] it = idAndToken(session(customer, Map.of()).text("url"));

        String stored = jdbc.sql("SELECT token_hash FROM portal_sessions WHERE id = :id").param("id", it[0])
                .query(String.class).single();
        assertThat(stored).doesNotContain(it[1]).hasSize(64);
        assertThat(portal.authorize(it[0], it[1])).isPresent();
        assertThat(portal.authorize(it[0], "wrong")).isEmpty();
        assertThat(portal.authorize("ps_nope", it[1])).isEmpty();
    }

    @Test
    void an_expired_session_no_longer_opens() {
        String[] it = idAndToken(session(customer, Map.of()).text("url"));
        jdbc.sql("UPDATE portal_sessions SET expires_at = now() - interval '1 second' WHERE id = :id").param("id", it[0]).update();

        assertThat(portal.authorize(it[0], it[1])).isEmpty();
    }

    @Test
    void by_default_the_customer_may_view_and_pay_only() {
        String[] it = idAndToken(session(customer, Map.of()).text("url"));

        var s = portal.authorize(it[0], it[1]).orElseThrow();
        assertThat(s.allowCancel()).isFalse();
        assertThat(s.allowCoupons()).isFalse();
        assertThat(PortalService.changePrices(s)).isEmpty();
    }

    @Test
    void the_merchant_chooses_the_extra_actions_and_the_prices() {
        String[] it = idAndToken(session(customer, Map.of("allow",
                Map.of("cancel", true, "coupons", true, "change_prices", List.of(yearly, premium)))).text("url"));

        var s = portal.authorize(it[0], it[1]).orElseThrow();
        assertThat(s.allowCancel()).isTrue();
        assertThat(s.allowCoupons()).isTrue();
        assertThat(PortalService.changePrices(s)).containsExactly(yearly, premium);
    }

    @Test
    void sessions_are_checked() {
        assertThat(session("cus_nope", Map.of()).status()).isEqualTo(404);
        assertThat(session(customer, Map.of("return_url", "javascript:alert(1)")).status()).isEqualTo(400);
        Response noPrice = session(customer, Map.of("allow", Map.of("change_prices", List.of("price_nope"))));
        assertThat(noPrice.status()).isEqualTo(422);
        assertThat(noPrice.text("code")).isEqualTo("price_not_found");
        String otherCustomer = post("other", "/v1/customers", Map.of("external_id", "u_" + UUID.randomUUID(), "country", "SN")).text("id");
        assertThat(session(otherCustomer, Map.of()).status()).as("another application's customer").isEqualTo(404);
    }
}
