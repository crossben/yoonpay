package dev.yoonpay.server.api;

import dev.yoonpay.server.webhook.InboundWebhookProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Demo mode: a visitor pays on Yoon's demo checkout page and the payment settles through the normal pipeline. */
class DemoTest extends ApiTest {

    @Autowired
    InboundWebhookProcessor processor;

    private final HttpClient browser = HttpClient.newHttpClient();

    private HttpResponse<String> open(String path, String form) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (form != null) {
            b.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form));
        }
        return browser.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Response demoPayment() {
        Map<String, Object> body = new HashMap<>(payment("wave"));
        body.put("description", "Thieboudienne <x2>");
        body.put("return_url", "https://shop.example/orders/42");
        Response p = post("demoapp", "/v1/payments", body);
        assertThat(p.text("provider")).isEqualTo("demo");
        return p;
    }

    @Test
    void a_visitor_pays_on_the_demo_page_and_the_payment_succeeds() throws Exception {
        Response p = demoPayment();
        String path = URI.create(p.text("checkout_url")).getPath();

        HttpResponse<String> page = open(path, null);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("5000 XOF").contains("Thieboudienne &lt;x2&gt;").contains("no real money");

        HttpResponse<String> decided = open(path, "decision=pay");
        assertThat(decided.statusCode()).isEqualTo(303);
        assertThat(decided.headers().firstValue("Location")).contains("https://shop.example/orders/42");

        processor.processPending(10);
        assertThat(get("demoapp", "/v1/payments/" + p.text("id")).text("status")).isEqualTo("succeeded");
    }

    @Test
    void declining_fails_the_payment() throws Exception {
        Response p = demoPayment();

        open(URI.create(p.text("checkout_url")).getPath(), "decision=decline");
        processor.processPending(10);

        assertThat(get("demoapp", "/v1/payments/" + p.text("id")).text("status")).isEqualTo("failed");
    }

    @Test
    void demo_payouts_complete() {
        Response po = post("demoapp", "/v1/payouts", Map.of("amount", 2500, "currency", "XOF", "country", "SN",
                "method", "wave", "recipient", Map.of("phone", "+221771234567")));

        assertThat(po.text("status")).isEqualTo("processing");
        assertThat(po.text("provider")).isEqualTo("demo");
    }
}
