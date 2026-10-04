package dev.yoonpay.provider.stripe;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.yoonpay.core.lifecycle.PaymentStatus;
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

/** Shapes from Stripe's API reference (docs.stripe.com/api); replace with recorded test-mode responses. */
class StripeProviderTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    static final String SECRET = "whsec_test";

    private static Map<String, String> credentials(String base) {
        Map<String, String> m = new HashMap<>();
        m.put("secret-key", "sk_test_123");
        m.put("webhook-secret", SECRET);
        m.put("base-url", base);
        return m;
    }

    private PaymentProvider provider() {
        return provider(ProviderHttp.withDefaults());
    }

    private PaymentProvider provider(ProviderHttp http) {
        return new StripeFactory(http).create(credentials("http://localhost:" + wm.getPort()));
    }

    private static CollectRequest collect() {
        return new CollectRequest("att_1", Money.of(5000, "XOF"), "SN", "card", null, "Order 1",
                URI.create("https://shop.example/return"), null);
    }

    private static final String SESSION = """
            {"id":"cs_test_1","object":"checkout.session","amount_total":5000,"currency":"xof","status":"complete",
             "payment_status":"paid","payment_intent":"pi_1","client_reference_id":"att_1","url":null}""";

    // ------------------------------------------------------------------ collect

    @Test
    void collect_creates_a_checkout_session_idempotently() {
        wm.stubFor(post("/v1/checkout/sessions").willReturn(okJson("""
                {"id":"cs_test_1","status":"open","payment_status":"unpaid","url":"https://checkout.stripe.com/c/pay/cs_test_1"}""")));

        assertThat(provider().collect(collect())).isEqualTo(new CallOutcome.Accepted(new ProviderReference("cs_test_1"),
                URI.create("https://checkout.stripe.com/c/pay/cs_test_1"), null));
        wm.verify(postRequestedFor(urlEqualTo("/v1/checkout/sessions"))
                .withHeader("Authorization", equalTo("Bearer sk_test_123"))
                .withHeader("Idempotency-Key", equalTo("att_1"))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withRequestBody(containing("mode=payment"))
                .withRequestBody(containing("client_reference_id=att_1"))
                .withRequestBody(containing("line_items%5B0%5D%5Bprice_data%5D%5Bcurrency%5D=xof"))
                .withRequestBody(containing("line_items%5B0%5D%5Bprice_data%5D%5Bunit_amount%5D=5000"))
                .withRequestBody(containing("success_url=https%3A%2F%2Fshop.example%2Freturn")));
    }

    @Test
    void collect_without_return_url_is_refused_before_any_call() {
        CollectRequest r = new CollectRequest("att_1", Money.of(5000, "XOF"), "SN", "card", null, null, null, null);

        assertThat(provider().collect(r)).isInstanceOf(CallOutcome.Rejected.class);
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    @Test
    void a_refusal_carries_stripes_error_code() {
        wm.stubFor(post("/v1/checkout/sessions").willReturn(jsonResponse("""
                {"error":{"type":"invalid_request_error","code":"amount_too_small","message":"Amount must be at least 0.50 usd"}}""", 400)));

        assertThat(provider().collect(collect()))
                .isEqualTo(new CallOutcome.Rejected("STRIPE_AMOUNT_TOO_SMALL", "Amount must be at least 0.50 usd"));
    }

    @Test
    void collect_5xx_timeout_reset_and_409_are_unknown_refused_connection_is_rejected() {
        wm.stubFor(post("/v1/checkout/sessions").willReturn(serverError()));
        assertThat(provider().collect(collect())).isInstanceOf(CallOutcome.Unknown.class);

        wm.stubFor(post("/v1/checkout/sessions").willReturn(jsonResponse("{\"error\":{\"type\":\"idempotency_error\"}}", 409)));
        assertThat(provider().collect(collect())).isInstanceOf(CallOutcome.Unknown.class);

        wm.stubFor(post("/v1/checkout/sessions").willReturn(okJson("{}").withFixedDelay(1500)));
        assertThat(provider(new ProviderHttp(Duration.ofSeconds(1), Duration.ofMillis(500))).collect(collect()))
                .isInstanceOf(CallOutcome.Unknown.class);

        wm.stubFor(post("/v1/checkout/sessions").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        assertThat(provider().collect(collect())).isInstanceOf(CallOutcome.Unknown.class);

        assertThat(new StripeFactory().create(credentials("http://localhost:1")).collect(collect()))
                .isInstanceOf(CallOutcome.Rejected.class);
    }

    @Test
    void session_status_and_amount() {
        wm.stubFor(get("/v1/checkout/sessions/cs_test_1").willReturn(okJson(SESSION)));

        assertThat(provider().status(new ProviderReference("cs_test_1")))
                .isEqualTo(new StatusResult<>(PaymentStatus.SUCCEEDED, "complete/paid", Money.of(5000, "XOF")));
    }

    // ------------------------------------------------------------------ refunds

    @Test
    void a_partial_refund_goes_to_the_sessions_payment_intent() {
        wm.stubFor(get("/v1/checkout/sessions/cs_test_1").willReturn(okJson(SESSION)));
        wm.stubFor(post("/v1/refunds").willReturn(okJson("""
                {"id":"re_1","object":"refund","amount":2000,"currency":"xof","status":"pending"}""")));

        CallOutcome outcome = provider().refund(new RefundRequest("rf_1", new ProviderReference("cs_test_1"), Money.of(2000, "XOF"), null));

        assertThat(outcome).isEqualTo(new CallOutcome.Accepted(new ProviderReference("re_1"), null, null));
        wm.verify(postRequestedFor(urlEqualTo("/v1/refunds"))
                .withHeader("Idempotency-Key", equalTo("rf_1"))
                .withRequestBody(containing("payment_intent=pi_1"))
                .withRequestBody(containing("amount=2000"))
                .withRequestBody(containing("metadata%5Byoon_reference%5D=rf_1")));
    }

    @Test
    void refund_of_an_unpaid_session_is_refused() {
        wm.stubFor(get("/v1/checkout/sessions/cs_test_1").willReturn(okJson("""
                {"id":"cs_test_1","status":"expired","payment_status":"unpaid","payment_intent":null}""")));

        assertThat(provider().refund(new RefundRequest("rf_1", new ProviderReference("cs_test_1"), Money.of(1, "XOF"), null)))
                .isInstanceOf(CallOutcome.Rejected.class);
        wm.verify(0, postRequestedFor(urlEqualTo("/v1/refunds")));
    }

    @Test
    void refund_too_large_is_refused_and_a_lost_one_is_unknown() {
        wm.stubFor(get("/v1/checkout/sessions/cs_test_1").willReturn(okJson(SESSION)));
        wm.stubFor(post("/v1/refunds").willReturn(jsonResponse("""
                {"error":{"type":"invalid_request_error","code":"charge_already_refunded","message":"Charge has already been refunded."}}""", 400)));
        RefundRequest r = new RefundRequest("rf_1", new ProviderReference("cs_test_1"), Money.of(5000, "XOF"), null);

        assertThat(provider().refund(r)).isEqualTo(new CallOutcome.Rejected("STRIPE_CHARGE_ALREADY_REFUNDED", "Charge has already been refunded."));

        wm.stubFor(post("/v1/refunds").willReturn(serverError()));
        assertThat(provider().refund(r)).isInstanceOf(CallOutcome.Unknown.class);
    }

    @Test
    void refund_status_and_lookup_by_metadata() {
        wm.stubFor(get("/v1/refunds/re_1").willReturn(okJson("""
                {"id":"re_1","amount":2000,"currency":"xof","status":"succeeded"}""")));
        wm.stubFor(get(urlPathEqualTo("/v1/refunds")).willReturn(okJson("""
                {"object":"list","data":[{"id":"re_0","metadata":{"yoon_reference":"rf_0"}},{"id":"re_1","metadata":{"yoon_reference":"rf_1"}}]}""")));

        assertThat(provider().refundStatus(new ProviderReference("re_1")))
                .isEqualTo(new StatusResult<>(RefundStatus.REFUNDED, "succeeded", Money.of(2000, "XOF")));
        assertThat(provider().lookup(Operation.REFUND, "rf_1")).contains(new ProviderReference("re_1"));
        assertThat(provider().lookup(Operation.REFUND, "rf_9")).isEmpty();
        assertThat(provider().lookup(Operation.COLLECT, "att_1")).isEmpty();
    }

    @Test
    void no_payouts() {
        assertThat(provider().payout(new PayoutRequest("po_1", Money.of(1, "XOF"), "SN", "card", "+221771234567", null)))
                .isInstanceOf(CallOutcome.Rejected.class);
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    // ------------------------------------------------------------------ callbacks

    private static final String EVENT = """
            {"id":"evt_1","object":"event","type":"checkout.session.completed","data":{"object":{"id":"cs_test_1","object":"checkout.session","payment_status":"paid"}}}""";

    private static String sign(String t, String secret) {
        return Signatures.hmacSha256Hex(secret, (t + "." + EVENT).getBytes(StandardCharsets.UTF_8));
    }

    private static InboundWebhook hook(String header) {
        return new InboundWebhook(header == null ? Map.of() : Map.of("Stripe-Signature", List.of(header)),
                EVENT.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void a_signed_event_is_valid_and_names_the_session() {
        assertThat(provider().verify(hook("t=1492774577,v1=" + sign("1492774577", SECRET) + ",v0=abc")))
                .isEqualTo(new WebhookVerification(true, new ProviderReference("cs_test_1")));
    }

    @Test
    void any_v1_may_match_during_secret_rolling() {
        assertThat(provider().verify(hook("t=1,v1=" + sign("1", "old") + ",v1=" + sign("1", SECRET))).signatureValid()).isTrue();
    }

    @Test
    void forged_tampered_v0_only_or_unsigned_events_are_invalid() {
        assertThat(provider().verify(hook("t=1,v1=" + sign("1", "other"))).signatureValid()).isFalse();
        assertThat(provider().verify(hook("t=2,v1=" + sign("1", SECRET))).signatureValid()).isFalse();
        assertThat(provider().verify(hook("t=1,v0=" + sign("1", SECRET))).signatureValid()).isFalse();
        assertThat(provider().verify(hook(null)).signatureValid()).isFalse();
    }

    // ------------------------------------------------------------------ configuration

    @Test
    void only_currencies_with_matching_minor_units_are_accepted() {
        Map<String, String> c = credentials("http://localhost:1");
        c.put("currencies", "XOF, EUR");
        assertThat(new StripeFactory().create(c).capabilities().toString()).contains("EUR");

        c.put("currencies", "UGX");
        assertThatThrownBy(() -> new StripeFactory().create(c)).hasMessageContaining("UGX");
    }
}
