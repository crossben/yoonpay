package dev.yoonpay.provider.naboopay;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.yoonpay.core.lifecycle.PaymentStatus;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Currency;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Response shapes come from the production PHP integrations and NabooPay's published SDK models
 * (not from a recorded sandbox session): replace with recorded responses when possible.
 */
class NabooPayProviderTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private static final String SECRET = "naboo-webhook-secret";

    private PaymentProvider provider() {
        return new NabooPayFactory(ProviderHttp.withDefaults()).create(Map.of(
                "API_KEY", "nk_test", "WEBHOOK_SECRET", SECRET, "base-url", "http://localhost:" + wm.getPort() + "/api/v1"));
    }

    private static CollectRequest collect(String method) {
        return new CollectRequest("att_3", Money.of(5000, "XOF"), "SN", method, null, "Order 3",
                URI.create("https://shop.example/return"), null);
    }

    @ParameterizedTest
    @CsvSource({"wave, WAVE", "orange_money, ORANGE_MONEY", "free_money, FREE_MONEY", "card, BANK"})
    void collect_sends_the_wallet_exactly_as_naboopay_spells_it(String method, String wallet) {
        wm.stubFor(post("/api/v1/transaction/create-transaction").willReturn(okJson("""
                {"order_id":"ord_1","checkout_url":"https://checkout.naboopay.com/ord_1","transaction_status":"pending"}""")));

        CallOutcome outcome = provider().collect(collect(method));

        assertThat(outcome).isEqualTo(new CallOutcome.Accepted(new ProviderReference("ord_1"),
                URI.create("https://checkout.naboopay.com/ord_1"), null));
        wm.verify(postRequestedFor(urlEqualTo("/api/v1/transaction/create-transaction"))
                .withHeader("Authorization", equalTo("Bearer nk_test"))
                .withRequestBody(matchingJsonPath("$.method_of_payment[0]", equalTo(wallet)))
                .withRequestBody(matchingJsonPath("$.products[0].amount", equalTo("5000")))
                .withRequestBody(matchingJsonPath("$.fees_customer_side", equalTo("true"))));
    }

    @Test
    void a_validation_error_is_a_rejection_with_naboopays_message() {
        wm.stubFor(post("/api/v1/transaction/create-transaction").willReturn(jsonResponse("""
                {"detail":[{"loc":["body","products",0,"amount"],"msg":"Input should be a valid integer"}]}""", 422)));

        assertThat(provider().collect(collect("wave")))
                .isEqualTo(new CallOutcome.Rejected("NABOOPAY_HTTP_422", "Input should be a valid integer"));
    }

    @Test
    void a_5xx_is_unknown() {
        wm.stubFor(post("/api/v1/transaction/create-transaction").willReturn(serverError()));

        assertThat(provider().collect(collect("wave"))).isInstanceOf(CallOutcome.Unknown.class);
    }

    @Test
    void currencies_with_minor_units_are_refused_before_any_call() {
        var req = new CollectRequest("att_4", Money.of(5000, "EUR"), "SN", "wave", null, null, null, null);

        assertThat(provider().collect(req)).isInstanceOfSatisfying(CallOutcome.Rejected.class, r ->
                assertThat(r.code()).isEqualTo("CURRENCY_NOT_SUPPORTED"));
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    @Test
    void status_is_read_by_order_id_with_the_paid_amount() {
        wm.stubFor(get(urlPathEqualTo("/api/v1/transaction/get-one-transaction")).withQueryParam("order_id", equalTo("ord_1"))
                .willReturn(okJson("""
                        {"order_id":"ord_1","transaction_status":"paid","amount":5000,"amount_to_pay":5100,"currency":"XOF","is_done":true}""")));

        var s = provider().status(new ProviderReference("ord_1"));

        assertThat(s.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(s.confirmedAmount()).isEqualTo(Money.of(5000, "XOF"));
    }

    @Test
    void a_partial_payment_is_pending_and_never_confirms_an_amount() {
        wm.stubFor(get(urlPathEqualTo("/api/v1/transaction/get-one-transaction")).willReturn(okJson("""
                {"order_id":"ord_1","transaction_status":"part_paid","amount":5000,"currency":"XOF"}""")));

        var s = provider().status(new ProviderReference("ord_1"));

        assertThat(s.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(s.rawStatus()).isEqualTo("part_paid");
        assertThat(s.confirmedAmount()).isNull();
    }

    @Test
    void callbacks_are_hmac_of_the_raw_body() {
        String body = """
                {"order_id":"ord_1","transaction_status":"paid","amount":5000,"currency":"XOF"}""";
        String sig = Signatures.hmacSha256Hex(SECRET, body.getBytes(StandardCharsets.UTF_8));

        var good = provider().verify(new InboundWebhook(Map.of("X-Signature", List.of(sig)), body.getBytes(StandardCharsets.UTF_8)));
        var bad = provider().verify(new InboundWebhook(Map.of("X-Signature", List.of("00" + sig.substring(2))), body.getBytes(StandardCharsets.UTF_8)));
        var none = provider().verify(new InboundWebhook(Map.of(), body.getBytes(StandardCharsets.UTF_8)));

        assertThat(good.signatureValid()).isTrue();
        assertThat(good.reference()).isEqualTo(new ProviderReference("ord_1"));
        assertThat(bad.signatureValid()).isFalse();
        assertThat(none.signatureValid()).isFalse();
    }

    @Test
    void only_collections_are_offered() {
        var caps = provider().capabilities();
        Currency xof = Currency.getInstance("XOF");

        assertThat(caps.supports(Operation.COLLECT, "SN", "orange_money", xof)).isTrue();
        assertThat(caps.supports(Operation.PAYOUT, "SN", "wave", xof)).isFalse();
        assertThat(caps.supports(Operation.REFUND, "SN", "wave", xof)).isFalse();
        assertThat(provider().payout(new PayoutRequest("p", Money.of(1, "XOF"), "SN", "wave", "+221771234567", null)))
                .isInstanceOf(CallOutcome.Rejected.class);
    }
}
