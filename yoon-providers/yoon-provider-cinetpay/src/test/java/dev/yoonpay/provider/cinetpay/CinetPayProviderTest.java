package dev.yoonpay.provider.cinetpay;

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
import dev.yoonpay.core.provider.RefundRequest;
import dev.yoonpay.core.provider.StatusResult;
import dev.yoonpay.provider.support.ProviderHttp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Shapes from CinetPay's own SDKs and their test fixtures (github.com/cinetpay/cinetpay-python),
 * not from recorded sandbox exchanges.
 */
class CinetPayProviderTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private static Map<String, String> credentials(String base) {
        Map<String, String> m = new HashMap<>();
        m.put("countries", "CI,SN");
        m.put("api-key-ci", "sk_test_ci");
        m.put("api-password-ci", "pw_ci");
        m.put("api-key-sn", "sk_test_sn");
        m.put("api-password-sn", "pw_sn");
        m.put("customer-email", "payments@shop.example");
        m.put("base-url", base);
        return m;
    }

    private PaymentProvider provider() {
        return new CinetPayFactory(ProviderHttp.withDefaults()).create(credentials("http://localhost:" + wm.getPort()));
    }

    @BeforeEach
    void login() {
        wm.stubFor(post("/v1/oauth/login").willReturn(okJson("{\"code\":200,\"status\":\"OK\",\"access_token\":\"jwt_1\"}")));
    }

    private static CollectRequest collect(long amount) {
        return new CollectRequest("att_1", Money.of(amount, "XOF"), "CI", "orange_money", "+2250707000001", "Order 1",
                URI.create("https://shop.example/return"), URI.create("https://yoon.example/v1/hooks/cinetpay/app"));
    }

    private static PayoutRequest payout(long amount) {
        return new PayoutRequest("po_0199a3f0c2e47a1b9c3d5e6f7a8b9c0d", Money.of(amount, "XOF"), "SN", "wave",
                "+221771234567", URI.create("https://yoon.example/v1/hooks/cinetpay/app"));
    }

    @Test
    void collect_logs_in_for_the_country_and_initialises_a_payment() {
        wm.stubFor(post("/v1/payment").willReturn(okJson("""
                {"code":200,"status":"OK","payment_token":"pt_1","notify_token":"nt_1","transaction_id":"txn_1",
                 "merchant_transaction_id":"att_1","payment_url":"https://checkout.cinetpay.net/pay/pt_1",
                 "details":{"code":2001,"status":"INITIATED","must_be_redirected":true}}""")));

        assertThat(provider().collect(collect(5000))).isEqualTo(new CallOutcome.Accepted(new ProviderReference("CI:att_1"),
                URI.create("https://checkout.cinetpay.net/pay/pt_1"), null));
        wm.verify(postRequestedFor(urlEqualTo("/v1/oauth/login"))
                .withRequestBody(matchingJsonPath("$.api_key", equalTo("sk_test_ci"))));
        wm.verify(postRequestedFor(urlEqualTo("/v1/payment"))
                .withHeader("Authorization", equalTo("Bearer jwt_1"))
                .withRequestBody(matchingJsonPath("$.merchant_transaction_id", equalTo("att_1")))
                .withRequestBody(matchingJsonPath("$.amount", equalTo("5000")))
                .withRequestBody(matchingJsonPath("$.currency", equalTo("XOF")))
                .withRequestBody(matchingJsonPath("$.payment_method", equalTo("OM_CI")))
                .withRequestBody(matchingJsonPath("$.client_email", equalTo("payments@shop.example")))
                .withRequestBody(matchingJsonPath("$.notify_url", equalTo("https://yoon.example/v1/hooks/cinetpay/app"))));
    }

    @Test
    void amounts_outside_cinetpays_limits_are_refused_before_any_call() {
        assertThat(provider().collect(collect(50))).isInstanceOf(CallOutcome.Rejected.class);
        assertThat(provider().payout(payout(100))).isInstanceOf(CallOutcome.Rejected.class);
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    @Test
    void a_duplicate_transaction_is_unknown_never_failed() {
        wm.stubFor(post("/v1/payment").willReturn(jsonResponse("""
                {"code":1200,"status":"TRANSACTION_EXIST","description":"Transaction already exists"}""", 400)));

        assertThat(provider().collect(collect(5000))).isInstanceOf(CallOutcome.Unknown.class);
    }

    @Test
    void an_expired_token_is_a_refusal_and_the_next_call_logs_in_again() {
        wm.stubFor(post("/v1/payment").willReturn(jsonResponse("""
                {"code":1003,"status":"EXPIRED_TOKEN","description":"Token has expired"}""", 401)));
        PaymentProvider p = provider();

        assertThat(p.collect(collect(5000))).isEqualTo(new CallOutcome.Rejected("CINETPAY_EXPIRED_TOKEN", "Token has expired"));
        p.collect(collect(5000));
        wm.verify(2, postRequestedFor(urlEqualTo("/v1/oauth/login")));
    }

    @Test
    void payment_status() {
        wm.stubFor(get("/v1/payment/att_1").willReturn(okJson("""
                {"code":100,"status":"SUCCESS","merchant_transaction_id":"att_1","transaction_id":"txn_1"}""")));

        assertThat(provider().status(new ProviderReference("CI:att_1")))
                .isEqualTo(new StatusResult<>(PaymentStatus.SUCCEEDED, "SUCCESS", null));
    }

    @Test
    void payout_transfers_to_the_operator_with_a_short_id() {
        String tx = CinetPayProvider.txId("po_0199a3f0c2e47a1b9c3d5e6f7a8b9c0d");
        wm.stubFor(post("/v1/transfer").willReturn(okJson("""
                {"code":200,"status":"PENDING","merchant_transaction_id":"%s","transaction_id":"txn_t","amount":"5000","currency":"XOF"}"""
                .formatted(tx))));

        assertThat(provider().payout(payout(5000))).isEqualTo(new CallOutcome.Accepted(new ProviderReference("SN:" + tx), null, null));
        wm.verify(postRequestedFor(urlEqualTo("/v1/transfer"))
                .withRequestBody(matchingJsonPath("$.merchant_transaction_id", equalTo(tx)))
                .withRequestBody(matchingJsonPath("$.payment_method", equalTo("WAVE_SN")))
                .withRequestBody(matchingJsonPath("$.phone_number", equalTo("+221771234567"))));
    }

    @Test
    void insufficient_balance_is_a_refusal() {
        wm.stubFor(post("/v1/transfer").willReturn(jsonResponse("""
                {"code":2005,"status":"INSUFFICIENT_BALANCE","description":"Insufficient balance"}""", 400)));

        assertThat(provider().payout(payout(5000)))
                .isEqualTo(new CallOutcome.Rejected("CINETPAY_INSUFFICIENT_BALANCE", "Insufficient balance"));
    }

    @Test
    void an_operation_error_on_a_transfer_is_unknown() {
        wm.stubFor(post("/v1/transfer").willReturn(jsonResponse("{\"code\":-1,\"status\":\"OPERATION_ERROR\"}", 400)));

        assertThat(provider().payout(payout(5000))).isInstanceOf(CallOutcome.Unknown.class);
    }

    @Test
    void payout_status_carries_the_amount() {
        wm.stubFor(get("/v1/transfer/SN-unused").willReturn(notFound()));
        wm.stubFor(get(urlPathMatching("/v1/transfer/y.*")).willReturn(okJson("""
                {"code":100,"status":"SUCCESS","amount":"5000","currency":"XOF"}""")));
        String tx = CinetPayProvider.txId("po_0199a3f0c2e47a1b9c3d5e6f7a8b9c0d");

        assertThat(provider().payoutStatus(new ProviderReference("SN:" + tx)))
                .isEqualTo(new StatusResult<>(PayoutStatus.PAID, "SUCCESS", Money.of(5000, "XOF")));
    }

    @Test
    void lookup_searches_every_configured_country() {
        wm.stubFor(get("/v1/payment/att_1").willReturn(okJson("""
                {"code":100,"status":"PENDING","merchant_transaction_id":"att_1"}""")));

        assertThat(provider().lookup(Operation.COLLECT, "att_1")).isPresent();
        assertThat(provider().lookup(Operation.REFUND, "rf_1")).isEmpty();
    }

    @Test
    void no_refunds_and_notifications_are_never_trusted() {
        assertThat(provider().refund(new RefundRequest("rf_1", new ProviderReference("CI:att_1"), Money.of(1, "XOF"), null)))
                .isInstanceOf(CallOutcome.Rejected.class);
        byte[] body = "{\"notify_token\":\"nt_1\",\"merchant_transaction_id\":\"att_1\",\"transaction_id\":\"txn_1\"}"
                .getBytes(StandardCharsets.UTF_8);
        assertThat(provider().verify(new InboundWebhook(Map.of("Content-Type", List.of("application/json")), body)).signatureValid())
                .isFalse();
    }

    @Test
    void configuration_is_checked() {
        Map<String, String> c = credentials("http://localhost:1");
        c.put("countries", "CD");
        assertThatThrownBy(() -> new CinetPayFactory().create(c)).hasMessageContaining("CD");

        Map<String, String> d = credentials("http://localhost:1");
        d.remove("api-password-sn");
        assertThatThrownBy(() -> new CinetPayFactory().create(d)).hasMessageContaining("api-password-sn");
    }
}
