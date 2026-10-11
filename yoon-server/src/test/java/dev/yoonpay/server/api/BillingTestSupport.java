package dev.yoonpay.server.api;

import dev.yoonpay.core.billing.BillingPeriods;
import dev.yoonpay.core.billing.Interval;
import dev.yoonpay.core.lifecycle.PaymentStatus;
import dev.yoonpay.core.provider.ProviderReference;
import dev.yoonpay.server.checkout.CheckoutController;
import dev.yoonpay.server.webhook.InboundWebhookProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dev.yoonpay.server.TestProviders.FAKE_ONE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared setup for billing tests: a customer, a catalog, and paying an invoice the real way.
 *
 * Prepaid subscriptions (ADR-0026, ADR-0027) end to end: every period is paid the real way — the
 * customer picks a method on the hosted checkout, the provider confirms, a signed callback arrives,
 * Yoon asks the provider, and only then the invoice and the subscription move.
 */
abstract class BillingTestSupport extends ApiTest {

    @Autowired
    InboundWebhookProcessor processor;

    @Autowired
    io.micrometer.core.instrument.MeterRegistry meters;

    protected String customer;
    protected String monthly;     // 15 000 XOF / month
    protected String yearly;      // 150 000 XOF / year (cheaper per day: a downgrade)
    protected String premium;     // 30 000 XOF / month (an upgrade)
    protected String euro;        // 20 EUR / month (another currency)

    @BeforeEach
    void catalog() {
        customer = post("shop", "/v1/customers", Map.of("external_id", "user_" + UUID.randomUUID(), "country", "SN",
                "phone", "771234567")).text("id");
        Response pro = post("shop", "/v1/plans", Map.of("slug", "pro_" + UUID.randomUUID().toString().substring(0, 8),
                "name", "Pro", "features", Map.of("seats", 5),
                "prices", List.of(Map.of("currency", "XOF", "amount", 15000, "interval", "month"),
                        Map.of("currency", "XOF", "amount", 150000, "interval", "year"),
                        Map.of("currency", "EUR", "amount", 2000, "interval", "month"))));
        monthly = pro.json().path("prices").get(0).path("id").asString();
        yearly = pro.json().path("prices").get(1).path("id").asString();
        euro = pro.json().path("prices").get(2).path("id").asString();
        Response max = post("shop", "/v1/plans", Map.of("slug", "max_" + UUID.randomUUID().toString().substring(0, 8),
                "name", "Max", "prices", List.of(Map.of("currency", "XOF", "amount", 30000, "interval", "month"))));
        premium = max.json().path("prices").get(0).path("id").asString();
    }

    // ------------------------------------------------------------------ helpers

    protected Response subscribe(String price) {
        Response r = post("shop", "/v1/subscriptions", Map.of("customer", customer, "price", price,
                "return_url", "https://shop.example/billing"));
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return r;
    }

    /** Pays an invoice (JSON) the way a customer would, and lets Yoon settle it. */
    protected void payInvoice(tools.jackson.databind.JsonNode invoice) {
        String paymentId = invoice.path("payment").asString();
        String url = invoice.path("checkout_url").asString();
        String token = URI.create(url).getQuery().substring(2);
        Response chosen = send(null, "POST", "/checkout/api/" + paymentId + "/attempts", Map.of("method", "wave"), null,
                Map.of(CheckoutController.TOKEN_HEADER, token));
        assertThat(chosen.status()).as(chosen.body()).isEqualTo(200);
        ProviderReference ref = new ProviderReference(get("shop", "/v1/payments/" + paymentId).text("provider_reference"));
        FAKE_ONE.settle(ref, PaymentStatus.SUCCEEDED);
        var hook = FAKE_ONE.sendWebhook(ref);
        postRaw("/v1/hooks/fakeone/" + app("shop").id(), hook.headers(), hook.rawBody());
        processor.processPending(20);
    }

    protected Response sub(String id) {
        return get("shop", "/v1/subscriptions/" + id);
    }

    protected Response renew(String id) {
        Response r = post("shop", "/v1/subscriptions/" + id + "/renew", Map.of("return_url", "https://shop.example/billing"));
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return r;
    }

    protected static Instant at(Response r, String field) {
        return Instant.parse(r.text(field));
    }

    protected List<String> eventTypes(String resourceId) {
        return jdbc.sql("SELECT type FROM outbound_events WHERE resource_id = :id ORDER BY created_at, id")
                .param("id", resourceId).query(String.class).list();
    }
}
