package dev.yoonpay.server.api;

import dev.yoonpay.server.checkout.CheckoutController;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Customer actions from the portal (ADR-0031): only what the merchant allowed, recorded as `portal`. */
class PortalActionsTest extends BillingTestSupport {

    private record Session(String id, String token) {
    }

    private Session session(Map<String, Object> allow) {
        Map<String, Object> body = new HashMap<>();
        body.put("return_url", "https://shop.example/account");
        if (allow != null) {
            body.put("allow", allow);
        }
        URI u = URI.create(post("shop", "/v1/customers/" + customer + "/portal_sessions", body).text("url"));
        String path = u.getPath();
        return new Session(path.substring(path.lastIndexOf('/') + 1), u.getQuery().substring(2));
    }

    private Response act(Session s, Map<String, Object> body) {
        Map<String, Object> b = new HashMap<>(body);
        b.putIfAbsent("attempt", UUID.randomUUID().toString());
        return send(null, "POST", "/checkout/api/portal/" + s.id() + "/actions", b, null,
                Map.of(CheckoutController.TOKEN_HEADER, s.token()));
    }

    private String paidSubscription() {
        String id = subscribe(monthly).text("id");
        payInvoice(sub(id).json().path("latest_invoice"));
        return id;
    }

    private List<String> causes(String subscriptionId) {
        return jdbc.sql("SELECT cause FROM status_events WHERE resource_id = :id ORDER BY id").param("id", subscriptionId)
                .query(String.class).list();
    }

    @Test
    void the_customer_can_always_renew_and_is_sent_to_the_checkout() {
        String id = paidSubscription();

        Response r = act(session(null), Map.of("action", "renew", "subscription", id));

        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(r.text("checkout_url")).contains("/checkout/pay_");
        assertThat(sub(id).json().path("latest_invoice").path("status").asString()).isEqualTo("open");
    }

    @Test
    void cancel_and_resume_need_the_merchants_permission_and_are_recorded_as_portal() {
        String id = paidSubscription();

        Response refused = act(session(null), Map.of("action", "cancel", "subscription", id));
        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.text("code")).isEqualTo("action_not_allowed");
        assertThat(sub(id).json().path("cancel_at_period_end").asBoolean()).isFalse();

        Session allowed = session(Map.of("cancel", true));
        assertThat(act(allowed, Map.of("action", "cancel", "subscription", id)).status()).isEqualTo(200);
        assertThat(sub(id).json().path("cancel_at_period_end").asBoolean()).isTrue();
        assertThat(sub(id).text("status")).as("still active until the period end").isEqualTo("active");
        assertThat(causes(id)).endsWith("portal");

        assertThat(act(allowed, Map.of("action", "resume", "subscription", id)).status()).isEqualTo(200);
        assertThat(sub(id).json().path("cancel_at_period_end").asBoolean()).isFalse();
    }

    @Test
    void only_the_listed_prices_are_offered_and_an_upgrade_goes_to_the_checkout() {
        String id = paidSubscription();
        Session s = session(Map.of("change_prices", List.of(premium, yearly)));

        assertThat(act(s, Map.of("action", "change", "subscription", id, "price", euro)).status()).isEqualTo(403);

        Response upgrade = act(s, Map.of("action", "change", "subscription", id, "price", premium));
        assertThat(upgrade.status()).as(upgrade.body()).isEqualTo(200);
        assertThat(upgrade.text("checkout_url")).contains("/checkout/pay_");
        assertThat(sub(id).json().path("latest_invoice").path("kind").asString()).isEqualTo("upgrade");
    }

    @Test
    void a_cheaper_plan_waits_for_the_period_end() {
        String id = paidSubscription();

        Response r = act(session(Map.of("change_prices", List.of(yearly))), Map.of("action", "change", "subscription", id, "price", yearly));

        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(r.json().path("checkout_url").isNull()).isTrue();
        assertThat(sub(id).json().path("pending_change").path("price").path("id").asString()).isEqualTo(yearly);
    }

    @Test
    void coupons_need_the_merchants_permission() {
        String id = subscribe(monthly).text("id");
        String code = "P" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        post("shop", "/v1/coupons", Map.of("code", code, "percent_off", 10, "duration", "once"));

        assertThat(act(session(null), Map.of("action", "coupon", "subscription", id, "coupon", code)).status()).isEqualTo(403);
        Response r = act(session(Map.of("coupons", true)), Map.of("action", "coupon", "subscription", id, "coupon", code));

        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(sub(id).json().path("discount").path("code").asString()).isEqualTo(code);
        assertThat(causes(id)).endsWith("portal");
    }

    @Test
    void another_customers_subscription_is_not_found() {
        String other = post("shop", "/v1/customers", Map.of("external_id", "u_" + UUID.randomUUID(), "country", "SN")).text("id");
        String theirs = post("shop", "/v1/subscriptions", Map.of("customer", other, "price", monthly,
                "return_url", "https://shop.example/billing")).text("id");

        Response r = act(session(Map.of("cancel", true)), Map.of("action", "renew", "subscription", theirs));

        assertThat(r.status()).isEqualTo(404);
    }

    @Test
    void a_double_click_acts_once() {
        String id = paidSubscription();
        Session s = session(Map.of("cancel", true));
        String attempt = UUID.randomUUID().toString();
        int before = causes(id).size();

        act(s, Map.of("action", "cancel", "subscription", id, "attempt", attempt));
        act(s, Map.of("action", "cancel", "subscription", id, "attempt", attempt));

        assertThat(causes(id).size() - before).isEqualTo(1);
    }

    @Test
    void an_expired_session_cannot_act() {
        String id = paidSubscription();
        Session s = session(Map.of("cancel", true));
        jdbc.sql("UPDATE portal_sessions SET expires_at = now() - interval '1 second' WHERE id = :id").param("id", s.id()).update();

        assertThat(act(s, Map.of("action", "cancel", "subscription", id)).status()).isEqualTo(404);
        assertThat(sub(id).json().path("cancel_at_period_end").asBoolean()).isFalse();
    }
}
