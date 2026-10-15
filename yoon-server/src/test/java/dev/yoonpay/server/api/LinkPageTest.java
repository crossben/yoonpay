package dev.yoonpay.server.api;

import dev.yoonpay.server.checkout.CheckoutFilter;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static dev.yoonpay.server.TestProviders.FAKE_ONE;
import static org.assertj.core.api.Assertions.assertThat;

/** The public side of payment links (ADR-0030): page, view, "Pay", states, QR code. */
class LinkPageTest extends ApiTest {

    private record Link(String id, String code) {
    }

    private Link link(Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(Map.of("currency", "XOF", "country", "SN", "description", "Tote bag"));
        body.putAll(extra);
        Response r = post("shop", "/v1/payment_links", body);
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        String url = r.text("url");
        return new Link(r.text("id"), url.substring(url.lastIndexOf('/') + 1));
    }

    private Response view(Link l) {
        return send(null, "GET", "/checkout/api/links/" + l.code(), null, null, Map.of());
    }

    private Response pay(Link l, Map<String, Object> body) {
        return send(null, "POST", "/checkout/api/links/" + l.code() + "/payments", body, null, Map.of());
    }

    private static String attempt() {
        return UUID.randomUUID().toString();
    }

    private String paymentIdOf(String checkoutUrl) {
        String path = java.net.URI.create(checkoutUrl).getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    @Test
    void a_fixed_link_opens_and_pays_through_an_ordinary_hosted_payment() {
        Link l = link(Map.of("amount", 5000));

        Response page = send(null, "GET", "/l/" + l.code(), null, null, Map.of());
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body()).contains("link.js");
        assertThat(page.headers().get("Content-Security-Policy")).containsExactly(CheckoutFilter.CSP);

        Response v = view(l);
        assertThat(v.text("state")).isEqualTo("open");
        assertThat(v.json().path("amount").asLong()).isEqualTo(5000);
        assertThat(v.text("merchant")).isEqualTo("shop");
        assertThat(v.body()).doesNotContain(l.id()).as("the view names no internal id");

        String attempt = attempt();
        Response paid = pay(l, Map.of("attempt", attempt));
        assertThat(paid.status()).as(paid.body()).isEqualTo(201);
        String paymentId = paymentIdOf(paid.text("checkout_url"));
        Response payment = get("shop", "/v1/payments/" + paymentId);
        assertThat(payment.text("payment_link")).isEqualTo(l.id());
        assertThat(payment.json().path("amount").asLong()).isEqualTo(5000);
        assertThat(payment.text("checkout")).isEqualTo("hosted");

        Response again = pay(l, Map.of("attempt", attempt));
        assertThat(again.text("checkout_url")).as("a double click pays one payment").isEqualTo(paid.text("checkout_url"));
        Response other = pay(l, Map.of("attempt", attempt()));
        assertThat(other.text("checkout_url")).as("another visit is another payment").isNotEqualTo(paid.text("checkout_url"));
    }

    @Test
    void the_customer_types_the_amount_within_the_bounds() {
        Link l = link(Map.of("min_amount", 500, "max_amount", 10000));

        assertThat(view(l).json().path("amount").isNull()).isTrue();
        assertThat(pay(l, Map.of("attempt", attempt())).status()).as("no amount").isEqualTo(400);
        assertThat(pay(l, Map.of("attempt", attempt(), "amount", 100)).status()).as("below").isEqualTo(400);
        assertThat(pay(l, Map.of("attempt", attempt(), "amount", 20000)).status()).as("above").isEqualTo(400);
        Response ok = pay(l, Map.of("attempt", attempt(), "amount", 2500));
        assertThat(ok.status()).as(ok.body()).isEqualTo(201);
        assertThat(get("shop", "/v1/payments/" + paymentIdOf(ok.text("checkout_url"))).json().path("amount").asLong()).isEqualTo(2500);
    }

    @Test
    void a_fixed_amount_cannot_be_changed_by_the_page() {
        Link l = link(Map.of("amount", 5000));

        assertThat(pay(l, Map.of("attempt", attempt(), "amount", 1)).status()).isEqualTo(400);
    }

    @Test
    void a_single_use_link_is_used_up_once_paid() {
        Link l = link(Map.of("amount", 5000, "max_payments", 1));
        String paymentId = paymentIdOf(pay(l, Map.of("attempt", attempt())).text("checkout_url"));
        jdbc.sql("UPDATE payments SET status = 'SUCCEEDED' WHERE id = :id").param("id", paymentId).update();

        assertThat(view(l).text("state")).isEqualTo("used_up");
        Response r = pay(l, Map.of("attempt", attempt()));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.text("code")).isEqualTo("link_used_up");
        assertThat(get("shop", "/v1/payment_links/" + l.id()).json().path("payments_count").asLong()).isEqualTo(1);
    }

    @Test
    void archived_and_expired_links_say_so() {
        Link archived = link(Map.of("amount", 5000));
        send("shop", "PATCH", "/v1/payment_links/" + archived.id(), Map.of("active", false), "k-" + UUID.randomUUID());
        Link expired = link(Map.of("amount", 5000));
        jdbc.sql("UPDATE payment_links SET expires_at = now() - interval '1 minute' WHERE id = :id").param("id", expired.id()).update();

        assertThat(view(archived).text("state")).isEqualTo("inactive");
        assertThat(pay(archived, Map.of("attempt", attempt())).text("code")).isEqualTo("link_inactive");
        assertThat(view(expired).text("state")).isEqualTo("expired");
        assertThat(pay(expired, Map.of("attempt", attempt())).text("code")).isEqualTo("link_expired");
    }

    @Test
    void an_unknown_code_is_404() {
        String code = "AAAAAAAAAAAAAAAAAAAAAA";

        assertThat(send(null, "GET", "/checkout/api/links/" + code, null, null, Map.of()).status()).isEqualTo(404);
        assertThat(send(null, "GET", "/l/" + code, null, null, Map.of()).status()).isEqualTo(404);
        assertThat(send(null, "GET", "/l/" + code + "/qr.svg", null, null, Map.of()).status()).isEqualTo(404);
    }

    @Test
    void the_qr_code_is_an_svg() {
        Link l = link(Map.of("amount", 5000));

        Response qr = send(null, "GET", "/l/" + l.code() + "/qr.svg", null, null, Map.of());

        assertThat(qr.status()).isEqualTo(200);
        assertThat(qr.headers().get("Content-Type").get(0)).startsWith("image/svg+xml");
        assertThat(qr.body()).startsWith("<svg").contains("<path d=\"M");
    }

    @Test
    void a_paid_link_payment_settles_like_any_payment_and_counts() {
        Link l = link(Map.of("amount", 5000));
        String checkoutUrl = pay(l, Map.of("attempt", attempt())).text("checkout_url");
        String paymentId = paymentIdOf(checkoutUrl);
        String token = java.net.URI.create(checkoutUrl).getQuery().substring(2);
        send(null, "POST", "/checkout/api/" + paymentId + "/attempts", Map.of("method", "wave"), null,
                Map.of(dev.yoonpay.server.checkout.CheckoutController.TOKEN_HEADER, token));
        var ref = new dev.yoonpay.core.provider.ProviderReference(get("shop", "/v1/payments/" + paymentId).text("provider_reference"));
        FAKE_ONE.settle(ref, dev.yoonpay.core.lifecycle.PaymentStatus.SUCCEEDED);
        var hook = FAKE_ONE.sendWebhook(ref);
        postRaw("/v1/hooks/fakeone/" + app("shop").id(), hook.headers(), hook.rawBody());
        processor.processPending(20);

        assertThat(get("shop", "/v1/payments/" + paymentId).text("status")).isEqualTo("succeeded");
        Response link = get("shop", "/v1/payment_links/" + l.id());
        assertThat(link.json().path("payments_count").asLong()).isEqualTo(1);
        assertThat(link.json().path("amount_collected").asLong()).isEqualTo(5000);
    }

    @org.springframework.beans.factory.annotation.Autowired
    dev.yoonpay.server.webhook.InboundWebhookProcessor processor;
}
