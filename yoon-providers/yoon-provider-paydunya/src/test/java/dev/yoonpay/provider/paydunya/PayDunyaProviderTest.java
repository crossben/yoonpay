package dev.yoonpay.provider.paydunya;

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

import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Currency;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Response shapes come from the production PHP integrations this adapter replaces (not from a
 * recorded sandbox session): replace with recorded responses when sandbox access is available.
 */
class PayDunyaProviderTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private static final String MASTER = "test-master-key";

    private PaymentProvider provider() {
        return provider(ProviderHttp.withDefaults(), "http://localhost:" + wm.getPort());
    }

    private PaymentProvider provider(ProviderHttp http, String base) {
        return new PayDunyaFactory(http).create(Map.of(
                "MASTERKEY", MASTER, "PRIVATE_KEY", "test-private", "token", "test-token",
                "base-url", base + "/api/v1", "disburse-base-url", base + "/api/v2"));
    }

    private static CollectRequest collect() {
        return new CollectRequest("att_1", Money.of(5000, "XOF"), "SN", "wave", "+221771234567", "Order 1042",
                URI.create("https://shop.example/return"), URI.create("https://yoon.example/v1/hooks/paydunya/app"));
    }

    private static PayoutRequest payout(long amount) {
        return new PayoutRequest("po_1", Money.of(amount, "XOF"), "SN", "wave", "+221771234567",
                URI.create("https://yoon.example/v1/hooks/paydunya/app"));
    }

    // ------------------------------------------------------------------ collect

    @Test
    void collect_creates_a_hosted_invoice_and_returns_its_token_and_url() {
        wm.stubFor(post("/api/v1/checkout-invoice/create").willReturn(okJson("""
                {"response_code":"00","response_text":"https://app.paydunya.com/sandbox-checkout/invoice/tok_123","description":"ok","token":"tok_123"}""")));

        CallOutcome outcome = provider().collect(collect());

        assertThat(outcome).isEqualTo(new CallOutcome.Accepted(new ProviderReference("tok_123"),
                URI.create("https://app.paydunya.com/sandbox-checkout/invoice/tok_123"), null));
        wm.verify(postRequestedFor(urlEqualTo("/api/v1/checkout-invoice/create"))
                .withHeader("PAYDUNYA-MASTER-KEY", equalTo(MASTER))
                .withHeader("PAYDUNYA-PRIVATE-KEY", equalTo("test-private"))
                .withHeader("PAYDUNYA-TOKEN", equalTo("test-token"))
                .withRequestBody(matchingJsonPath("$.invoice.total_amount", equalTo("5000")))
                .withRequestBody(matchingJsonPath("$.custom_data.yoon_attempt", equalTo("att_1")))
                .withRequestBody(matchingJsonPath("$.actions.callback_url", equalTo("https://yoon.example/v1/hooks/paydunya/app"))));
    }

    @Test
    void a_non_00_response_code_is_a_definite_rejection() {
        wm.stubFor(post("/api/v1/checkout-invoice/create").willReturn(okJson("""
                {"response_code":"1001","response_text":"Invalid Masterkey"}""")));

        assertThat(provider().collect(collect())).isEqualTo(new CallOutcome.Rejected("PAYDUNYA_1001", "Invalid Masterkey"));
    }

    @Test
    void a_server_error_is_unknown_never_a_rejection() {
        wm.stubFor(post("/api/v1/checkout-invoice/create").willReturn(serverError()));

        assertThat(provider().collect(collect())).isInstanceOf(CallOutcome.Unknown.class);
    }

    @Test
    void an_unreadable_success_is_unknown() {
        wm.stubFor(post("/api/v1/checkout-invoice/create").willReturn(ok("<html>maintenance</html>")));

        assertThat(provider().collect(collect())).isInstanceOf(CallOutcome.Unknown.class);
    }

    @Test
    void a_read_timeout_is_unknown() {
        wm.stubFor(post("/api/v1/checkout-invoice/create").willReturn(okJson("{}").withFixedDelay(1500)));
        ProviderHttp fast = new ProviderHttp(Duration.ofSeconds(1), Duration.ofMillis(300));

        assertThat(provider(fast, "http://localhost:" + wm.getPort()).collect(collect())).isInstanceOf(CallOutcome.Unknown.class);
    }

    @Test
    void a_refused_connection_is_a_definite_not_sent() throws Exception {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }

        CallOutcome outcome = provider(ProviderHttp.withDefaults(), "http://localhost:" + closedPort).collect(collect());

        assertThat(outcome).isInstanceOfSatisfying(CallOutcome.Rejected.class, r ->
                assertThat(r.code()).isEqualTo("PROVIDER_UNAVAILABLE"));
    }

    // ------------------------------------------------------------------ status

    @Test
    void status_is_read_from_the_confirm_api_with_the_amount() {
        wm.stubFor(get("/api/v1/checkout-invoice/confirm/tok_123").willReturn(okJson("""
                {"response_code":"00","status":"completed","invoice":{"token":"tok_123","total_amount":5000}}""")));

        var s = provider().status(new ProviderReference("tok_123"));

        assertThat(s.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(s.rawStatus()).isEqualTo("completed");
        assertThat(s.confirmedAmount()).isEqualTo(Money.of(5000, "XOF"));
    }

    @Test
    void an_unreachable_confirm_api_is_no_answer() {
        wm.stubFor(get("/api/v1/checkout-invoice/confirm/tok_123").willReturn(serverError()));

        assertThat(provider().status(new ProviderReference("tok_123")).status()).isNull();
    }

    // ------------------------------------------------------------------ payouts

    @Test
    void a_payout_prepares_then_submits_with_the_local_number() {
        wm.stubFor(post("/api/v2/disburse/get-invoice").willReturn(okJson("""
                {"response_code":"00","response_text":"ok","disburse_token":"dt_9"}""")));
        wm.stubFor(post("/api/v2/disburse/submit-invoice").willReturn(okJson("""
                {"response_code":"00","status":"pending","transaction_id":"tx_1"}""")));

        CallOutcome outcome = provider().payout(payout(10000));

        assertThat(outcome).isEqualTo(new CallOutcome.Accepted(new ProviderReference("dt_9"), null, null));
        wm.verify(postRequestedFor(urlEqualTo("/api/v2/disburse/get-invoice"))
                .withRequestBody(matchingJsonPath("$.account_alias", equalTo("771234567")))
                .withRequestBody(matchingJsonPath("$.withdraw_mode", equalTo("wave-senegal")))
                .withRequestBody(matchingJsonPath("$.amount", equalTo("10000")))
                .withRequestBody(matchingJsonPath("$.disburse_id", equalTo("po_1"))));
        wm.verify(postRequestedFor(urlEqualTo("/api/v2/disburse/submit-invoice"))
                .withRequestBody(matchingJsonPath("$.disburse_invoice", equalTo("dt_9"))));
    }

    @Test
    void the_disburse_token_may_be_called_token() {
        wm.stubFor(post("/api/v2/disburse/get-invoice").willReturn(okJson("""
                {"response_code":"00","token":"dt_alt"}""")));
        wm.stubFor(post("/api/v2/disburse/submit-invoice").willReturn(okJson("""
                {"response_code":"00"}""")));

        assertThat(provider().payout(payout(1000))).isEqualTo(new CallOutcome.Accepted(new ProviderReference("dt_alt"), null, null));
    }

    @Test
    void below_the_minimum_nothing_is_sent() {
        assertThat(provider().payout(payout(199))).isInstanceOfSatisfying(CallOutcome.Rejected.class, r ->
                assertThat(r.code()).isEqualTo("AMOUNT_TOO_LOW"));
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    @Test
    void a_failed_prepare_step_is_a_rejection_because_no_money_moved() {
        wm.stubFor(post("/api/v2/disburse/get-invoice").willReturn(serverError()));

        assertThat(provider().payout(payout(1000))).isInstanceOf(CallOutcome.Rejected.class);
        wm.verify(0, postRequestedFor(urlEqualTo("/api/v2/disburse/submit-invoice")));
    }

    @Test
    void a_lost_submit_is_unknown_and_keeps_the_token_for_the_status_check() {
        wm.stubFor(post("/api/v2/disburse/get-invoice").willReturn(okJson("""
                {"response_code":"00","disburse_token":"dt_lost"}""")));
        wm.stubFor(post("/api/v2/disburse/submit-invoice").willReturn(serverError()));

        CallOutcome outcome = provider().payout(payout(1000));

        assertThat(outcome).isInstanceOfSatisfying(CallOutcome.Unknown.class, u ->
                assertThat(u.reference()).isEqualTo(new ProviderReference("dt_lost")));
    }

    @Test
    void a_refused_submit_is_a_rejection() {
        wm.stubFor(post("/api/v2/disburse/get-invoice").willReturn(okJson("""
                {"response_code":"00","disburse_token":"dt_x"}""")));
        wm.stubFor(post("/api/v2/disburse/submit-invoice").willReturn(okJson("""
                {"response_code":"4002","response_text":"Solde insuffisant"}""")));

        assertThat(provider().payout(payout(1000))).isEqualTo(new CallOutcome.Rejected("PAYDUNYA_4002", "Solde insuffisant"));
    }

    @Test
    void payout_status_is_read_from_check_status() {
        wm.stubFor(post("/api/v2/disburse/check-status").withRequestBody(matchingJsonPath("$.disburse_invoice", equalTo("dt_9")))
                .willReturn(okJson("""
                        {"response_code":"00","status":"success","amount":10000}""")));

        var s = provider().payoutStatus(new ProviderReference("dt_9"));

        assertThat(s.status()).isEqualTo(PayoutStatus.PAID);
        assertThat(s.confirmedAmount()).isEqualTo(Money.of(10000, "XOF"));
    }

    // ------------------------------------------------------------------ callbacks

    private static InboundWebhook hook(String contentType, String body) {
        return new InboundWebhook(Map.of("Content-Type", List.of(contentType)), body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void a_json_ipn_with_the_master_key_hash_verifies() {
        String hash = Signatures.sha512Hex(MASTER);
        var v = provider().verify(hook("application/json", """
                {"data":{"hash":"%s","status":"completed","invoice":{"token":"tok_123"}}}""".formatted(hash)));

        assertThat(v.signatureValid()).isTrue();
        assertThat(v.reference()).isEqualTo(new ProviderReference("tok_123"));
    }

    @Test
    void a_form_encoded_ipn_with_the_hash_at_the_root_verifies() {
        String hash = Signatures.sha512Hex(MASTER);
        var v = provider().verify(hook("application/x-www-form-urlencoded",
                "hash=" + hash + "&data%5Bstatus%5D=completed&data%5Binvoice%5D%5Btoken%5D=tok_form"));

        assertThat(v.signatureValid()).isTrue();
        assertThat(v.reference()).isEqualTo(new ProviderReference("tok_form"));
    }

    @Test
    void a_wrong_hash_does_not_verify_but_the_reference_is_still_read() {
        var v = provider().verify(hook("application/json", """
                {"hash":"deadbeef","data":{"invoice":{"token":"tok_123"}}}"""));

        assertThat(v.signatureValid()).isFalse();
        assertThat(v.reference()).isEqualTo(new ProviderReference("tok_123"));
    }

    @Test
    void a_flat_disburse_callback_carries_the_disburse_token() {
        var v = provider().verify(hook("application/json", """
                {"status":"success","disburse_invoice":"dt_9","transaction_id":"tx_1"}"""));

        assertThat(v.signatureValid()).isFalse();
        assertThat(v.reference()).isEqualTo(new ProviderReference("dt_9"));
    }

    @Test
    void the_empty_reachability_probe_is_harmless() {
        var v = provider().verify(hook("application/json", ""));

        assertThat(v.signatureValid()).isFalse();
        assertThat(v.reference()).isNull();
    }

    // ------------------------------------------------------------------ misc

    @Test
    void capabilities_cover_senegal_collect_and_payout_but_not_refunds() {
        var caps = provider().capabilities();
        Currency xof = Currency.getInstance("XOF");

        assertThat(caps.supports(Operation.COLLECT, "SN", "wave", xof)).isTrue();
        assertThat(caps.supports(Operation.PAYOUT, "SN", "orange_money", xof)).isTrue();
        assertThat(caps.supports(Operation.REFUND, "SN", "wave", xof)).isFalse();
        assertThat(caps.supports(Operation.COLLECT, "CI", "wave", xof)).isFalse();
    }

    @Test
    void a_missing_credential_is_named_but_no_value_is_printed() {
        assertThatThrownBy(() -> new PayDunyaFactory().create(Map.of("master-key", "secret-value", "token", "t")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private-key")
                .hasMessageNotContaining("secret-value");
    }
}
