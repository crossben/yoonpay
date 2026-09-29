package dev.yoonpay.provider.dexpay;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.yoonpay.core.lifecycle.PaymentStatus;
import dev.yoonpay.core.lifecycle.PayoutStatus;
import dev.yoonpay.core.money.Money;
import dev.yoonpay.core.provider.CallOutcome;
import dev.yoonpay.core.provider.CollectRequest;
import dev.yoonpay.core.provider.InboundWebhook;
import dev.yoonpay.core.provider.Operation;
import dev.yoonpay.core.provider.PaymentProvider;
import dev.yoonpay.core.provider.PayoutRequest;
import dev.yoonpay.core.provider.ProviderReference;
import dev.yoonpay.provider.support.ProviderHttp;
import dev.yoonpay.provider.support.Signatures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Response shapes come from the production PHP integrations this adapter replaces (not from a
 * recorded sandbox session): replace with recorded responses when sandbox access is available.
 */
class DexPayProviderTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private static final String WEBHOOK_SECRET = "whsec_dashboard_value";

    private PaymentProvider provider() {
        return new DexPayFactory(ProviderHttp.withDefaults()).create(Map.of(
                "api-key", "pk_test", "api-secret", "sk_test", "webhook-secret", WEBHOOK_SECRET,
                "base-url", "http://localhost:" + wm.getPort() + "/api/v1"));
    }

    private static CollectRequest collect() {
        return new CollectRequest("att_7", Money.of(5000, "XOF"), "SN", "wave", null, "Order 7",
                URI.create("https://shop.example/return"), URI.create("https://yoon.example/v1/hooks/dexpay/app"));
    }

    private static PayoutRequest payout() {
        return new PayoutRequest("po_7", Money.of(10000, "XOF"), "SN", "orange_money", "+221771234567",
                URI.create("https://yoon.example/v1/hooks/dexpay/app"));
    }

    @Test
    void collect_opens_a_checkout_session_keyed_on_our_reference() {
        wm.stubFor(post("/api/v1/checkout-sessions").willReturn(okJson("""
                {"data":{"checkout_session_id":"cs_1","payment_url":"https://pay.dexpay.africa/cs_1","expires_at":"2026-09-30T10:00:00Z"}}""")));

        CallOutcome outcome = provider().collect(collect());

        assertThat(outcome).isEqualTo(new CallOutcome.Accepted(new ProviderReference("att_7"),
                URI.create("https://pay.dexpay.africa/cs_1"), null));
        wm.verify(postRequestedFor(urlEqualTo("/api/v1/checkout-sessions"))
                .withHeader("x-api-key", equalTo("pk_test"))
                .withoutHeader("x-api-secret")
                .withRequestBody(matchingJsonPath("$.reference", equalTo("att_7")))
                .withRequestBody(matchingJsonPath("$.amount", equalTo("5000")))
                .withRequestBody(matchingJsonPath("$.countryISO", equalTo("SN")))
                .withRequestBody(matchingJsonPath("$.webhook_url", equalTo("https://yoon.example/v1/hooks/dexpay/app"))));
    }

    @Test
    void a_flat_response_with_checkout_url_is_accepted_too() {
        wm.stubFor(post("/api/v1/checkout-sessions").willReturn(okJson("""
                {"id":"cs_2","checkout_url":"https://pay.dexpay.africa/cs_2"}""")));

        assertThat(provider().collect(collect())).isInstanceOf(CallOutcome.Accepted.class);
    }

    @Test
    void a_4xx_is_a_rejection_with_the_providers_message() {
        wm.stubFor(post("/api/v1/checkout-sessions").willReturn(jsonResponse("""
                {"message":"Invalid API key"}""", 401)));

        assertThat(provider().collect(collect())).isEqualTo(new CallOutcome.Rejected("DEXPAY_HTTP_401", "Invalid API key"));
    }

    @Test
    void a_5xx_is_unknown_but_the_reference_is_known() {
        wm.stubFor(post("/api/v1/checkout-sessions").willReturn(serverError()));

        assertThat(provider().collect(collect())).isEqualTo(new CallOutcome.Unknown("HTTP 500", new ProviderReference("att_7")));
    }

    @Test
    void status_uses_our_reference_and_both_keys() {
        wm.stubFor(get("/api/v1/checkout-sessions/att_7").willReturn(okJson("""
                {"data":{"reference":"att_7","status":"completed","amount":5000,"currency":"XOF","operator":"wave"}}""")));

        var s = provider().status(new ProviderReference("att_7"));

        assertThat(s.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(s.confirmedAmount()).isEqualTo(Money.of(5000, "XOF"));
        wm.verify(getRequestedFor(urlEqualTo("/api/v1/checkout-sessions/att_7"))
                .withHeader("x-api-key", equalTo("pk_test")).withHeader("x-api-secret", equalTo("sk_test")));
    }

    @Test
    void lookup_finds_a_session_whose_creation_answer_was_lost() {
        wm.stubFor(get("/api/v1/checkout-sessions/att_7").willReturn(okJson("""
                {"data":{"status":"pending"}}""")));
        wm.stubFor(get("/api/v1/checkout-sessions/att_never").willReturn(notFound()));

        assertThat(provider().lookup(Operation.COLLECT, "att_7")).contains(new ProviderReference("att_7"));
        assertThat(provider().lookup(Operation.COLLECT, "att_never")).isEmpty();
        assertThat(provider().lookup(Operation.REFUND, "x")).isEqualTo(Optional.empty());
    }

    @Test
    void payouts_send_our_reference_as_the_idempotency_key() {
        wm.stubFor(post("/api/v1/payouts").willReturn(jsonResponse("""
                {"data":{"reference":"po_7","status":"pending"}}""", 201)));

        assertThat(provider().payout(payout())).isEqualTo(new CallOutcome.Accepted(new ProviderReference("po_7"), null, null));
        wm.verify(postRequestedFor(urlEqualTo("/api/v1/payouts"))
                .withHeader("Idempotency-Key", equalTo("po_7"))
                .withHeader("x-api-secret", equalTo("sk_test"))
                .withRequestBody(matchingJsonPath("$.destination_phone", equalTo("+221771234567")))
                .withRequestBody(matchingJsonPath("$.destination_details.operator", equalTo("om_sn_payout"))));
    }

    @Test
    void a_refused_payout_is_a_rejection_but_a_conflict_or_5xx_is_unknown() {
        wm.stubFor(post("/api/v1/payouts").willReturn(jsonResponse("""
                {"message":"Insufficient balance"}""", 402)));
        assertThat(provider().payout(payout())).isEqualTo(new CallOutcome.Rejected("DEXPAY_HTTP_402", "Insufficient balance"));

        wm.stubFor(post("/api/v1/payouts").willReturn(jsonResponse("{}", 409)));
        assertThat(provider().payout(payout())).isInstanceOf(CallOutcome.Unknown.class);

        wm.stubFor(post("/api/v1/payouts").willReturn(serverError()));
        assertThat(provider().payout(payout())).isEqualTo(new CallOutcome.Unknown("HTTP 500", new ProviderReference("po_7")));
    }

    @Test
    void payout_status_reads_wrapped_or_flat_answers() {
        wm.stubFor(get("/api/v1/payouts/po_7").willReturn(okJson("""
                {"payout":{"reference":"po_7","status":"SUCCESS","amount":"10000"}}""")));

        var s = provider().payoutStatus(new ProviderReference("po_7"));

        assertThat(s.status()).isEqualTo(PayoutStatus.PAID);
        assertThat(s.confirmedAmount()).isEqualTo(Money.of(10000, "XOF"));
    }

    @Test
    void a_payout_by_an_unconfigured_operator_is_refused_before_any_call() {
        var r = provider().payout(new PayoutRequest("po_8", Money.of(1000, "XOF"), "SN", "free_money", "+221771234567", null));

        assertThat(r).isInstanceOf(CallOutcome.Rejected.class);
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    // ------------------------------------------------------------------ callbacks

    private static InboundWebhook hook(String header, String signature, String body) {
        return new InboundWebhook(Map.of(header, List.of(signature)), body.getBytes(StandardCharsets.UTF_8));
    }

    private static final String BODY = """
            {"event":"checkout.completed","data":{"reference":"att_7","status":"completed","amount":5000,"currency":"XOF"}}""";

    @Test
    void the_current_signature_header_verifies_over_the_raw_body() {
        String sig = Signatures.hmacSha256Hex(WEBHOOK_SECRET, BODY.getBytes(StandardCharsets.UTF_8));

        var v = provider().verify(hook("x-webhook-signature", sig, BODY));

        assertThat(v.signatureValid()).isTrue();
        assertThat(v.reference()).isEqualTo(new ProviderReference("att_7"));
    }

    @Test
    void the_legacy_header_and_a_sha256_prefix_are_accepted() {
        String sig = Signatures.hmacSha256Hex(WEBHOOK_SECRET, BODY.getBytes(StandardCharsets.UTF_8));

        assertThat(provider().verify(hook("X-DEXCHANGE-SIGNATURE", sig, BODY)).signatureValid()).isTrue();
        assertThat(provider().verify(hook("x-webhook-signature", "sha256=" + sig.toUpperCase(), BODY)).signatureValid()).isTrue();
    }

    @Test
    void signing_with_the_api_secret_instead_of_the_webhook_secret_does_not_verify() {
        String sig = Signatures.hmacSha256Hex("sk_test", BODY.getBytes(StandardCharsets.UTF_8));

        assertThat(provider().verify(hook("x-webhook-signature", sig, BODY)).signatureValid()).isFalse();
    }

    @Test
    void a_reformatted_body_does_not_verify() {
        String sig = Signatures.hmacSha256Hex(WEBHOOK_SECRET, BODY.getBytes(StandardCharsets.UTF_8));
        String reformatted = BODY.replace(",", ", ");

        assertThat(provider().verify(hook("x-webhook-signature", sig, reformatted)).signatureValid()).isFalse();
    }

    @Test
    void a_payout_callback_carries_the_merchant_reference() {
        String body = """
                {"event":"payout.completed","data":{"merchant_reference":"po_7","status":"completed"}}""";
        String sig = Signatures.hmacSha256Hex(WEBHOOK_SECRET, body.getBytes(StandardCharsets.UTF_8));

        assertThat(provider().verify(hook("x-webhook-signature", sig, body)).reference()).isEqualTo(new ProviderReference("po_7"));
    }
}
