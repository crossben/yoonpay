package dev.yoonpay.provider.wave;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.yoonpay.core.lifecycle.PaymentStatus;
import dev.yoonpay.core.lifecycle.PayoutStatus;
import dev.yoonpay.core.lifecycle.RefundStatus;
import dev.yoonpay.core.money.Money;
import dev.yoonpay.core.provider.CallOutcome;
import dev.yoonpay.core.provider.CollectRequest;
import dev.yoonpay.core.provider.InboundWebhook;
import dev.yoonpay.core.provider.Operation;
import dev.yoonpay.core.provider.PaymentProvider;
import dev.yoonpay.core.provider.PayoutRequest;
import dev.yoonpay.core.provider.ProviderReference;
import dev.yoonpay.core.provider.RefundRequest;
import dev.yoonpay.core.provider.StatusResult;
import dev.yoonpay.core.provider.WebhookVerification;
import dev.yoonpay.provider.support.ProviderHttp;
import dev.yoonpay.provider.support.Signatures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Request and response shapes come from Wave's public API documentation (docs.wave.com), not
 * from recorded exchanges: replace with recorded responses when a Wave Business key is available.
 */
class WaveProviderTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    static final String SECRET = "wave_sn_WHS_test";

    private static Map<String, String> credentials(String base) {
        Map<String, String> m = new HashMap<>();
        m.put("api-key", "wave_sn_prod_test");
        m.put("webhook-secret", SECRET);
        m.put("base-url", base);
        return m;
    }

    private PaymentProvider provider() {
        return provider(ProviderHttp.withDefaults());
    }

    private PaymentProvider provider(ProviderHttp http) {
        return new WaveFactory(http).create(credentials("http://localhost:" + wm.getPort()));
    }

    private static CollectRequest collect() {
        return new CollectRequest("att_1", Money.of(1000, "XOF"), "SN", "wave", "+221771234567", "Order 1",
                URI.create("https://shop.example/return"), URI.create("https://yoon.example/v1/hooks/wave/app"));
    }

    private static PayoutRequest payout() {
        return new PayoutRequest("po_1", Money.of(15000, "XOF"), "SN", "wave", "+221771234567", null);
    }

    private static final String SESSION = """
            {"id":"cos-18qq25rgr100a","amount":"1000","checkout_status":"complete","client_reference":"att_1",
             "currency":"XOF","payment_status":"succeeded","transaction_id":"TDH5TEWTLFE",
             "wave_launch_url":"https://pay.wave.com/c/cos-18qq25rgr100a"}""";

    // ------------------------------------------------------------------ collect

    @Test
    void collect_opens_a_checkout_session_with_our_reference() {
        wm.stubFor(post("/v1/checkout/sessions").willReturn(okJson("""
                {"id":"cos-1","checkout_status":"open","payment_status":"processing","amount":"1000","currency":"XOF",
                 "wave_launch_url":"https://pay.wave.com/c/cos-1"}""")));

        assertThat(provider().collect(collect())).isEqualTo(new CallOutcome.Accepted(new ProviderReference("cos-1"),
                URI.create("https://pay.wave.com/c/cos-1"), null));
        wm.verify(postRequestedFor(urlEqualTo("/v1/checkout/sessions"))
                .withHeader("Authorization", equalTo("Bearer wave_sn_prod_test"))
                .withRequestBody(matchingJsonPath("$.amount", equalTo("1000")))
                .withRequestBody(matchingJsonPath("$.currency", equalTo("XOF")))
                .withRequestBody(matchingJsonPath("$.client_reference", equalTo("att_1")))
                .withRequestBody(matchingJsonPath("$.success_url", equalTo("https://shop.example/return")))
                .withRequestBody(matchingJsonPath("$.error_url", equalTo("https://shop.example/return")))
                .withRequestBody(matchingJsonPath("$.restrict_payer_mobile", equalTo("+221771234567"))));
    }

    @Test
    void collect_needs_a_return_url() {
        CollectRequest r = new CollectRequest("att_1", Money.of(1000, "XOF"), "SN", "wave", null, null, null, null);

        assertThat(provider().collect(r)).isInstanceOf(CallOutcome.Rejected.class);
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    @Test
    void a_refusal_carries_waves_error_code() {
        wm.stubFor(post("/v1/checkout/sessions").willReturn(jsonResponse("""
                {"error":{"code":"request-validation-error","message":"Invalid amount","httpcode":400}}""", 400)));

        assertThat(provider().collect(collect()))
                .isEqualTo(new CallOutcome.Rejected("WAVE_REQUEST_VALIDATION_ERROR", "Invalid amount"));
    }

    @Test
    void collect_5xx_timeout_and_reset_are_unknown_refused_connection_is_rejected() {
        wm.stubFor(post("/v1/checkout/sessions").willReturn(serverError()));
        assertThat(provider().collect(collect())).isInstanceOf(CallOutcome.Unknown.class);

        wm.stubFor(post("/v1/checkout/sessions").willReturn(okJson("{}").withFixedDelay(1500)));
        assertThat(provider(new ProviderHttp(Duration.ofSeconds(1), Duration.ofMillis(500))).collect(collect()))
                .isInstanceOf(CallOutcome.Unknown.class);

        wm.stubFor(post("/v1/checkout/sessions").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        assertThat(provider().collect(collect())).isInstanceOf(CallOutcome.Unknown.class);

        assertThat(new WaveFactory().create(credentials("http://localhost:1")).collect(collect()))
                .isInstanceOf(CallOutcome.Rejected.class);
    }

    @Test
    void a_success_without_launch_url_is_unknown() {
        wm.stubFor(post("/v1/checkout/sessions").willReturn(okJson("{\"id\":\"cos-1\"}")));

        assertThat(provider().collect(collect())).isInstanceOf(CallOutcome.Unknown.class);
    }

    @Test
    void checkout_status_and_amount() {
        wm.stubFor(get("/v1/checkout/sessions/cos-18qq25rgr100a").willReturn(okJson(SESSION)));

        assertThat(provider().status(new ProviderReference("cos-18qq25rgr100a")))
                .isEqualTo(new StatusResult<>(PaymentStatus.SUCCEEDED, "succeeded/complete", Money.of(1000, "XOF")));
    }

    @Test
    void lookup_finds_a_session_or_payout_by_client_reference() {
        wm.stubFor(get(urlPathEqualTo("/v1/checkout/sessions/search")).withQueryParam("client_reference", equalTo("att_1"))
                .willReturn(okJson("{\"result\":[" + SESSION + "]}")));
        wm.stubFor(get(urlPathEqualTo("/v1/payouts/search")).willReturn(okJson("{\"result\":[]}")));

        assertThat(provider().lookup(Operation.COLLECT, "att_1")).contains(new ProviderReference("cos-18qq25rgr100a"));
        assertThat(provider().lookup(Operation.PAYOUT, "po_1")).isEmpty();
        assertThat(provider().lookup(Operation.REFUND, "rf_1")).isEmpty();
    }

    // ------------------------------------------------------------------ payouts

    @Test
    void payout_sends_an_idempotency_key_and_our_reference() {
        wm.stubFor(post("/v1/payout").willReturn(okJson("""
                {"id":"pt-185sewgm8100t","currency":"XOF","receive_amount":"15000","fee":"150","mobile":"+221771234567",
                 "client_reference":"po_1","status":"processing","timestamp":"2026-10-01T09:56:29Z"}""")));

        assertThat(provider().payout(payout()))
                .isEqualTo(new CallOutcome.Accepted(new ProviderReference("pt-185sewgm8100t"), null, null));
        wm.verify(postRequestedFor(urlEqualTo("/v1/payout"))
                .withHeader("Idempotency-Key", equalTo("po_1"))
                .withRequestBody(matchingJsonPath("$.receive_amount", equalTo("15000")))
                .withRequestBody(matchingJsonPath("$.mobile", equalTo("+221771234567")))
                .withRequestBody(matchingJsonPath("$.client_reference", equalTo("po_1"))));
    }

    @Test
    void insufficient_funds_is_a_refusal() {
        wm.stubFor(post("/v1/payout").willReturn(jsonResponse("""
                {"error":{"code":"insufficient-funds","message":"Insufficient funds","httpcode":422}}""", 422)));

        assertThat(provider().payout(payout())).isEqualTo(new CallOutcome.Rejected("WAVE_INSUFFICIENT_FUNDS", "Insufficient funds"));
    }

    @Test
    void payout_5xx_lost_answer_or_idempotency_conflict_is_unknown() {
        wm.stubFor(post("/v1/payout").willReturn(serverError()));
        assertThat(provider().payout(payout())).isInstanceOf(CallOutcome.Unknown.class);

        wm.stubFor(post("/v1/payout").willReturn(jsonResponse("""
                {"error":{"code":"idempotency-mismatch","message":"x","httpcode":409}}""", 409)));
        assertThat(provider().payout(payout())).isInstanceOf(CallOutcome.Unknown.class);

        wm.stubFor(post("/v1/payout").willReturn(okJson("{}")));
        assertThat(provider().payout(payout())).isInstanceOf(CallOutcome.Unknown.class);
    }

    @Test
    void payout_needs_a_phone_and_a_configured_country() {
        assertThat(provider().payout(new PayoutRequest("po_2", Money.of(1, "XOF"), "SN", "wave", null, null, "alias")))
                .isInstanceOf(CallOutcome.Rejected.class);
        assertThat(provider().payout(new PayoutRequest("po_3", Money.of(1, "XOF"), "CI", "wave", "+2250700000000", null)))
                .isInstanceOf(CallOutcome.Rejected.class);
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    @Test
    void payout_status() {
        wm.stubFor(get("/v1/payout/pt-1").willReturn(okJson("""
                {"id":"pt-1","status":"succeeded","receive_amount":"15000","currency":"XOF"}""")));

        assertThat(provider().payoutStatus(new ProviderReference("pt-1")))
                .isEqualTo(new StatusResult<>(PayoutStatus.PAID, "succeeded", Money.of(15000, "XOF")));
    }

    // ------------------------------------------------------------------ refunds

    @Test
    void refund_refunds_the_whole_session() {
        wm.stubFor(get("/v1/checkout/sessions/cos-18qq25rgr100a").willReturn(okJson(SESSION)));
        wm.stubFor(post("/v1/checkout/sessions/cos-18qq25rgr100a/refund").willReturn(ok()));

        assertThat(provider().refund(new RefundRequest("rf_1", new ProviderReference("cos-18qq25rgr100a"), Money.of(1000, "XOF"), null)))
                .isEqualTo(new CallOutcome.Accepted(new ProviderReference("cos-18qq25rgr100a"), null, null));
        assertThat(provider().refundStatus(new ProviderReference("cos-18qq25rgr100a")).status()).isEqualTo(RefundStatus.REFUNDED);
    }

    @Test
    void partial_refunds_are_refused_before_any_call() {
        wm.stubFor(get("/v1/checkout/sessions/cos-18qq25rgr100a").willReturn(okJson(SESSION)));

        CallOutcome outcome = provider().refund(new RefundRequest("rf_1", new ProviderReference("cos-18qq25rgr100a"), Money.of(400, "XOF"), null));

        assertThat(((CallOutcome.Rejected) outcome).code()).isEqualTo("PARTIAL_REFUND_NOT_SUPPORTED");
        wm.verify(0, postRequestedFor(urlMatching(".*/refund")));
    }

    @Test
    void a_failed_refund_is_refused_and_a_lost_one_is_unknown() {
        wm.stubFor(get("/v1/checkout/sessions/cos-18qq25rgr100a").willReturn(okJson(SESSION)));
        wm.stubFor(post("/v1/checkout/sessions/cos-18qq25rgr100a/refund").willReturn(jsonResponse("""
                {"error":{"code":"checkout-refund-failed","message":"Refund failed","httpcode":409}}""", 409)));
        RefundRequest r = new RefundRequest("rf_1", new ProviderReference("cos-18qq25rgr100a"), Money.of(1000, "XOF"), null);

        assertThat(provider().refund(r)).isEqualTo(new CallOutcome.Rejected("WAVE_CHECKOUT_REFUND_FAILED", "Refund failed"));

        wm.stubFor(post("/v1/checkout/sessions/cos-18qq25rgr100a/refund").willReturn(serverError()));
        assertThat(provider().refund(r)).isInstanceOf(CallOutcome.Unknown.class);
        assertThat(provider().refundStatus(new ProviderReference("cos-18qq25rgr100a")).status()).isNull();
    }

    // ------------------------------------------------------------------ callbacks

    private static final String EVENT = """
            {"id":"AE_ijzo7oGgrlM8","type":"checkout.session.completed","data":{"id":"cos-18qq25rgr100a","amount":"1000","currency":"XOF","payment_status":"succeeded"}}""";

    private static String sign(String t, String body, String secret) {
        return Signatures.hmacSha256Hex(secret, (t + body).getBytes(StandardCharsets.UTF_8));
    }

    private static InboundWebhook hook(String header, String value) {
        return new InboundWebhook(value == null ? Map.of() : Map.of(header, List.of(value)), EVENT.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void a_signed_callback_is_valid_and_names_the_session() {
        String header = "t=1639081943,v1=" + sign("1639081943", EVENT, SECRET);

        assertThat(provider().verify(hook("Wave-Signature", header)))
                .isEqualTo(new WebhookVerification(true, new ProviderReference("cos-18qq25rgr100a")));
    }

    @Test
    void any_v1_signature_may_match_during_key_rotation() {
        String header = "t=1639081943,v1=" + sign("1639081943", EVENT, "old") + ",v1=" + sign("1639081943", EVENT, SECRET);

        assertThat(provider().verify(hook("Wave-Signature", header)).signatureValid()).isTrue();
    }

    @Test
    void the_shared_secret_method_is_accepted() {
        assertThat(provider().verify(hook("Authorization", "Bearer " + SECRET)).signatureValid()).isTrue();
        assertThat(provider().verify(hook("Authorization", "Bearer nope")).signatureValid()).isFalse();
    }

    @Test
    void forged_tampered_or_unsigned_callbacks_are_invalid() {
        assertThat(provider().verify(hook("Wave-Signature", "t=1,v1=" + sign("1", EVENT, "other"))).signatureValid()).isFalse();
        assertThat(provider().verify(hook("Wave-Signature", "t=2,v1=" + sign("1", EVENT, SECRET))).signatureValid()).isFalse();
        assertThat(provider().verify(hook("Wave-Signature", "v1=" + sign("", EVENT, SECRET))).signatureValid()).isFalse();
        assertThat(provider().verify(hook("Wave-Signature", null)).signatureValid()).isFalse();
    }

    // ------------------------------------------------------------------ configuration

    @Test
    void countries_are_configurable_and_checked() {
        Map<String, String> c = credentials("http://localhost:1");
        c.put("countries", "SN, CI");
        assertThat(new WaveFactory().create(c).capabilities().toString()).contains("CI");

        c.put("countries", "UG");
        assertThatThrownBy(() -> new WaveFactory().create(c)).hasMessageContaining("UG");
    }
}
