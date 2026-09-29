package dev.yoonpay.server.api;

import dev.yoonpay.provider.support.Signatures;
import dev.yoonpay.server.settlement.Reconciler;
import dev.yoonpay.server.webhook.InboundWebhookProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.github.tomakehurst.wiremock.client.WireMock;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/** The whole gateway with a real provider adapter (DexPay) against a simulated DexPay API. */
class LiveProviderTest extends ApiTest {

    @Autowired
    InboundWebhookProcessor processor;

    @Autowired
    Reconciler reconciler;

    @BeforeEach
    void resetDexPay() {
        DEXPAY.resetAll();
    }

    private void dexpayStatus(String reference, String status) {
        DEXPAY.stubFor(WireMock.get("/api/v1/checkout-sessions/" + reference).willReturn(okJson("""
                {"data":{"reference":"%s","status":"%s","amount":5000,"currency":"XOF","operator":"wave"}}""".formatted(reference, status))));
    }

    private Response callback(String reference, String status) {
        byte[] body = """
                {"event":"checkout.completed","data":{"reference":"%s","status":"%s","amount":5000,"currency":"XOF"}}"""
                .formatted(reference, status).getBytes(StandardCharsets.UTF_8);
        String sig = Signatures.hmacSha256Hex(DEXPAY_WEBHOOK_SECRET, body);
        return postRaw("/v1/hooks/dexpay/" + app("live").id(),
                Map.of("x-webhook-signature", List.of(sig), "Content-Type", List.of("application/json")), body);
    }

    @Test
    void a_dexpay_payment_goes_from_checkout_to_settled() {
        DEXPAY.stubFor(WireMock.post("/api/v1/checkout-sessions").willReturn(okJson("""
                {"data":{"checkout_session_id":"cs_1","payment_url":"https://pay.dexpay.africa/cs_1"}}""")));

        Response p = post("live", "/v1/payments", payment("wave"));

        assertThat(p.status()).isEqualTo(201);
        assertThat(p.text("status")).isEqualTo("pending");
        assertThat(p.text("provider")).isEqualTo("dexpay");
        assertThat(p.text("checkout_url")).isEqualTo("https://pay.dexpay.africa/cs_1");
        String ref = p.text("provider_reference");
        DEXPAY.verify(postRequestedFor(urlEqualTo("/api/v1/checkout-sessions"))
                .withRequestBody(matchingJsonPath("$.reference", equalTo(ref)))
                .withRequestBody(matchingJsonPath("$.webhook_url", equalTo("https://yoon.example/v1/hooks/dexpay/" + app("live").id()))));

        dexpayStatus(ref, "completed");
        assertThat(callback(ref, "completed").status()).isEqualTo(200);
        processor.processPending(10);

        assertThat(get("live", "/v1/payments/" + p.text("id")).text("status")).isEqualTo("succeeded");
        DEXPAY.verify(getRequestedFor(urlEqualTo("/api/v1/checkout-sessions/" + ref)).withHeader("x-api-secret", equalTo("sk_test")));
    }

    @Test
    void a_signed_callback_that_lies_is_overruled_by_dexpays_status_api() {
        DEXPAY.stubFor(WireMock.post("/api/v1/checkout-sessions").willReturn(okJson("""
                {"data":{"payment_url":"https://pay.dexpay.africa/cs_2"}}""")));
        Response p = post("live", "/v1/payments", payment("wave"));
        String ref = p.text("provider_reference");

        dexpayStatus(ref, "pending");
        callback(ref, "completed");
        processor.processPending(10);

        assertThat(get("live", "/v1/payments/" + p.text("id")).text("status")).isEqualTo("pending");
    }

    @Test
    void a_dexpay_outage_during_creation_is_resolved_by_the_sweep() {
        DEXPAY.stubFor(WireMock.post("/api/v1/checkout-sessions").willReturn(serverError()));
        Response p = post("live", "/v1/payments", payment("wave"));
        assertThat(p.text("status")).isEqualTo("pending");
        String ref = p.text("provider_reference");
        assertThat(ref).as("DexPay keys on our reference: known even when the answer is lost").startsWith("att_");

        dexpayStatus(ref, "completed");
        jdbc.sql("UPDATE payments SET updated_at = now() - interval '2 minutes' WHERE id = :id").param("id", p.text("id")).update();
        reconciler.sweepPayments();

        assertThat(get("live", "/v1/payments/" + p.text("id")).text("status")).isEqualTo("succeeded");
    }

    @Test
    void a_dexpay_payout_is_sent_once_with_an_idempotency_key() {
        DEXPAY.stubFor(WireMock.post("/api/v1/payouts").willReturn(jsonResponse("""
                {"data":{"status":"pending"}}""", 201)));

        Response po = post("live", "/v1/payouts", Map.of("amount", 10000, "currency", "XOF", "country", "SN",
                "method", "orange_money", "recipient", Map.of("phone", "771234567")));

        assertThat(po.text("status")).isEqualTo("processing");
        DEXPAY.verify(1, postRequestedFor(urlEqualTo("/api/v1/payouts"))
                .withHeader("Idempotency-Key", equalTo(po.text("id")))
                .withRequestBody(matchingJsonPath("$.destination_phone", equalTo("+221771234567"))));
    }

    @Test
    void refunds_on_dexpay_are_refused_clearly() {
        DEXPAY.stubFor(WireMock.post("/api/v1/checkout-sessions").willReturn(okJson("""
                {"data":{"payment_url":"https://pay.dexpay.africa/cs_3"}}""")));
        String id = post("live", "/v1/payments", payment("wave")).text("id");
        jdbc.sql("UPDATE payments SET status = 'SUCCEEDED' WHERE id = :id").param("id", id).update();

        Response r = post("live", "/v1/payments/" + id + "/refunds", Map.of());

        assertThat(r.status()).isEqualTo(422);
        assertThat(r.text("code")).isEqualTo("refund_not_supported");
    }
}
