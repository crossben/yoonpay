package dev.yoonpay.provider.pispi;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Request and response shapes come from the API Business specification 1.5.0, not from recorded
 * sandbox exchanges (ADR-0019 lists what is still unverified).
 */
class PiSpiProviderTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    static final String CUSTOMER = "c0ffee00-0000-4000-8000-000000000001";
    static final String MERCHANT = "merchant-alias-0000-0000-000000000000";
    static final String SECRET = "whsec_pispi";

    static Map<String, String> credentials(String baseUrl) {
        Map<String, String> m = new HashMap<>();
        m.put("base-url", baseUrl + "/piz/v1");
        m.put("token-url", baseUrl + "/oauth/token");
        m.put("client-id", "cid");
        m.put("client-secret", "csecret");
        m.put("api-key", "akey");
        m.put("merchant-alias", MERCHANT);
        m.put("webhook-secret", SECRET);
        return m;
    }

    private PaymentProvider provider() {
        return provider(ProviderHttp.withDefaults());
    }

    private PaymentProvider provider(ProviderHttp http) {
        return new PiSpiFactory(http).create(credentials("http://localhost:" + wm.getPort()));
    }

    @BeforeEach
    void token() {
        wm.stubFor(post("/oauth/token").willReturn(okJson("""
                {"access_token":"tok_1","token_type":"Bearer","expires_in":3600}""")));
    }

    private static CollectRequest collect(String alias) {
        return new CollectRequest("att_1", Money.of(5000, "XOF"), "SN", "pispi", null, "Order 1",
                null, URI.create("https://yoon.example/v1/hooks/pispi/app"), alias);
    }

    private static PayoutRequest payout(String alias) {
        return new PayoutRequest("po_1", Money.of(10000, "XOF"), "CI", "pispi", alias == null ? "+2250700000000" : null,
                null, alias);
    }

    // ------------------------------------------------------------------ collect

    @Test
    void collect_sends_an_ecommerce_payment_request_to_the_customer_alias() {
        wm.stubFor(post("/piz/v1/demandes-paiements").willReturn(okJson("""
                {"txId":"att_1","statut":"INITIE","montant":5000}""")));

        CallOutcome outcome = provider().collect(collect(CUSTOMER));

        assertThat(outcome).isInstanceOf(CallOutcome.Accepted.class);
        assertThat(((CallOutcome.Accepted) outcome).reference()).isEqualTo(new ProviderReference("att_1"));
        wm.verify(postRequestedFor(urlEqualTo("/oauth/token"))
                .withHeader("Authorization", equalTo("Basic " + Base64.getEncoder().encodeToString("cid:csecret".getBytes())))
                .withRequestBody(containing("grant_type=client_credentials")));
        wm.verify(postRequestedFor(urlEqualTo("/piz/v1/demandes-paiements"))
                .withHeader("Authorization", equalTo("Bearer tok_1"))
                .withHeader("x-api-key", equalTo("akey"))
                .withRequestBody(matchingJsonPath("$.txId", equalTo("att_1")))
                .withRequestBody(matchingJsonPath("$.payeurAlias", equalTo(CUSTOMER)))
                .withRequestBody(matchingJsonPath("$.payeAlias", equalTo(MERCHANT)))
                .withRequestBody(matchingJsonPath("$.categorie", equalTo("521")))
                .withRequestBody(matchingJsonPath("$.confirmation", equalTo("false")))
                .withRequestBody(matchingJsonPath("$.montant", equalTo("5000"))));
    }

    @Test
    void the_token_is_reused_while_valid() {
        wm.stubFor(post("/piz/v1/demandes-paiements").willReturn(okJson("{}")));
        PaymentProvider p = provider();

        p.collect(collect(CUSTOMER));
        p.collect(collect(CUSTOMER));

        wm.verify(1, postRequestedFor(urlEqualTo("/oauth/token")));
    }

    @Test
    void collect_without_an_alias_is_refused_before_any_call() {
        assertThat(provider().collect(collect(null)))
                .isEqualTo(new CallOutcome.Rejected("PI_ALIAS_REQUIRED", "PI-SPI needs the customer's PI alias (customer.pi_alias)"));
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    @Test
    void no_token_means_nothing_was_sent() {
        wm.stubFor(post("/oauth/token").willReturn(status(401)));

        assertThat(provider().collect(collect(CUSTOMER))).isInstanceOf(CallOutcome.Rejected.class);
        wm.verify(0, postRequestedFor(urlEqualTo("/piz/v1/demandes-paiements")));
    }

    @Test
    void an_unknown_alias_is_a_refusal() {
        wm.stubFor(post("/piz/v1/demandes-paiements").willReturn(jsonResponse("""
                {"statutRaison":"BE23","detail":"Alias inconnu"}""", 400)));

        assertThat(provider().collect(collect(CUSTOMER))).isEqualTo(new CallOutcome.Rejected("PISPI_BE23", "Alias inconnu"));
    }

    @Test
    void a_duplicate_txid_means_our_first_request_arrived() {
        wm.stubFor(post("/piz/v1/demandes-paiements").willReturn(jsonResponse("""
                {"statutRaison":"DU03"}""", 409)));

        assertThat(provider().collect(collect(CUSTOMER)))
                .isEqualTo(new CallOutcome.Unknown("duplicate txId (DU03)", new ProviderReference("att_1")));
    }

    @Test
    void a_5xx_is_unknown() {
        wm.stubFor(post("/piz/v1/demandes-paiements").willReturn(serverError()));

        assertThat(provider().collect(collect(CUSTOMER))).isEqualTo(new CallOutcome.Unknown("HTTP 500", new ProviderReference("att_1")));
    }

    @Test
    void a_timeout_is_unknown() {
        wm.stubFor(post("/piz/v1/demandes-paiements").willReturn(okJson("{}").withFixedDelay(1500)));
        ProviderHttp slow = new ProviderHttp(Duration.ofSeconds(1), Duration.ofMillis(500));

        assertThat(provider(slow).collect(collect(CUSTOMER))).isInstanceOf(CallOutcome.Unknown.class);
    }

    @Test
    void a_dropped_connection_is_unknown() {
        wm.stubFor(post("/piz/v1/demandes-paiements").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThat(provider().collect(collect(CUSTOMER))).isInstanceOf(CallOutcome.Unknown.class);
    }

    @Test
    void a_refused_connection_is_a_refusal() {
        PaymentProvider p = new PiSpiFactory().create(credentials("http://localhost:1"));

        assertThat(p.collect(collect(CUSTOMER))).isInstanceOf(CallOutcome.Rejected.class);
    }

    @Test
    void request_status_and_amount() {
        wm.stubFor(get("/piz/v1/demandes-paiements/att_1").willReturn(okJson("""
                {"txId":"att_1","statut":"IRREVOCABLE","montant":5000}""")));

        assertThat(provider().status(new ProviderReference("att_1")))
                .isEqualTo(new StatusResult<>(PaymentStatus.SUCCEEDED, "IRREVOCABLE", Money.of(5000, "XOF")));
    }

    @Test
    void lookup_finds_a_request_by_our_reference() {
        wm.stubFor(get("/piz/v1/demandes-paiements/att_1").willReturn(okJson("""
                {"txId":"att_1","statut":"INITIE"}""")));
        wm.stubFor(get("/piz/v1/demandes-paiements/att_2").willReturn(notFound()));

        assertThat(provider().lookup(Operation.COLLECT, "att_1")).contains(new ProviderReference("att_1"));
        assertThat(provider().lookup(Operation.COLLECT, "att_2")).isEmpty();
        assertThat(provider().lookup(Operation.REFUND, "rf_1")).isEmpty();
    }

    // ------------------------------------------------------------------ payouts

    @Test
    void payout_sends_from_the_merchant_alias() {
        wm.stubFor(post("/piz/v1/paiements-envoyes").willReturn(okJson("{}")));

        assertThat(provider().payout(payout("r3c1p13n-0000-4000-8000-000000000002")))
                .isEqualTo(new CallOutcome.Accepted(new ProviderReference("po_1"), null, null));
        wm.verify(postRequestedFor(urlEqualTo("/piz/v1/paiements-envoyes"))
                .withRequestBody(matchingJsonPath("$.txId", equalTo("po_1")))
                .withRequestBody(matchingJsonPath("$.payeurAlias", equalTo(MERCHANT)))
                .withRequestBody(matchingJsonPath("$.payeAlias", equalTo("r3c1p13n-0000-4000-8000-000000000002")))
                .withRequestBody(matchingJsonPath("$.montant", equalTo("10000"))));
    }

    @Test
    void payout_without_an_alias_is_refused_before_any_call() {
        assertThat(provider().payout(payout(null))).isInstanceOf(CallOutcome.Rejected.class);
        wm.verify(0, postRequestedFor(urlEqualTo("/piz/v1/paiements-envoyes")));
    }

    @Test
    void payout_5xx_is_unknown_never_a_failure() {
        wm.stubFor(post("/piz/v1/paiements-envoyes").willReturn(status(502)));

        assertThat(provider().payout(payout("alias"))).isEqualTo(new CallOutcome.Unknown("HTTP 502", new ProviderReference("po_1")));
    }

    @Test
    void payout_status() {
        wm.stubFor(get("/piz/v1/paiements-envoyes/po_1").willReturn(okJson("""
                {"txId":"po_1","statut":"REJETE","statutRaison":"AC06","montant":10000}""")));

        assertThat(provider().payoutStatus(new ProviderReference("po_1")).status()).isEqualTo(PayoutStatus.FAILED);
    }

    // ------------------------------------------------------------------ refunds

    @Test
    void refund_returns_the_received_payment_found_by_the_request_txid() {
        wm.stubFor(get(urlPathEqualTo("/piz/v1/paiements-recus")).withQueryParam("txId", equalTo("att_1"))
                .willReturn(okJson("""
                        {"data":[{"txId":"att_1","end2endId":"E2E123","montant":5000,"statut":"IRREVOCABLE"}]}""")));
        wm.stubFor(put("/piz/v1/paiements/E2E123/retours").willReturn(okJson("{}")));

        CallOutcome outcome = provider().refund(new RefundRequest("rf_1", new ProviderReference("att_1"), Money.of(5000, "XOF"), null));

        assertThat(outcome).isEqualTo(new CallOutcome.Accepted(new ProviderReference("E2E123"), null, null));
        wm.verify(putRequestedFor(urlEqualTo("/piz/v1/paiements/E2E123/retours")).withHeader("Authorization", equalTo("Bearer tok_1")));
    }

    @Test
    void partial_refunds_are_refused() {
        wm.stubFor(get(urlPathEqualTo("/piz/v1/paiements-recus")).willReturn(okJson("""
                {"data":[{"txId":"att_1","end2endId":"E2E123","montant":5000}]}""")));

        CallOutcome outcome = provider().refund(new RefundRequest("rf_1", new ProviderReference("att_1"), Money.of(2000, "XOF"), null));

        assertThat(outcome).isInstanceOf(CallOutcome.Rejected.class);
        assertThat(((CallOutcome.Rejected) outcome).code()).isEqualTo("PARTIAL_REFUND_NOT_SUPPORTED");
        wm.verify(0, putRequestedFor(anyUrl()));
    }

    @Test
    void refund_of_an_unknown_payment_is_refused() {
        wm.stubFor(get(urlPathEqualTo("/piz/v1/paiements-recus")).willReturn(okJson("{\"data\":[]}")));

        assertThat(provider().refund(new RefundRequest("rf_1", new ProviderReference("att_1"), Money.of(5000, "XOF"), null)))
                .isInstanceOf(CallOutcome.Rejected.class);
    }

    @Test
    void refund_status_reads_the_return_status() {
        wm.stubFor(get("/piz/v1/paiements/E2E123").willReturn(okJson("""
                {"end2endId":"E2E123","statut":"IRREVOCABLE","retourStatut":"IRREVOCABLE","montant":5000}""")));

        assertThat(provider().refundStatus(new ProviderReference("E2E123")))
                .isEqualTo(new StatusResult<>(RefundStatus.REFUNDED, "IRREVOCABLE", Money.of(5000, "XOF")));
    }

    // ------------------------------------------------------------------ callbacks

    private static final String EVENT = """
            {"data":[{"evCode":"RTP_ENVOYE_ACCEPTE","evDate":"2026-09-30T10:00:00Z","txId":"att_1","end2endId":"E2E123","montant":5000}],"meta":{}}""";

    @Test
    void a_hex_signature_is_accepted() {
        byte[] body = EVENT.getBytes(StandardCharsets.UTF_8);
        WebhookVerification v = provider().verify(hook(body, Signatures.hmacSha256Hex(SECRET, body)));

        assertThat(v).isEqualTo(new WebhookVerification(true, new ProviderReference("att_1")));
    }

    @Test
    void a_base64_signature_is_accepted() throws Exception {
        byte[] body = EVENT.getBytes(StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));

        assertThat(provider().verify(hook(body, Base64.getEncoder().encodeToString(mac.doFinal(body)))).signatureValid()).isTrue();
    }

    @Test
    void forged_or_unsigned_callbacks_are_invalid() {
        byte[] body = EVENT.getBytes(StandardCharsets.UTF_8);

        assertThat(provider().verify(hook(body, Signatures.hmacSha256Hex("other", body))).signatureValid()).isFalse();
        assertThat(provider().verify(hook(body, null)).signatureValid()).isFalse();
        assertThat(provider().verify(hook(body, "not base64 !")).signatureValid()).isFalse();
    }

    private static InboundWebhook hook(byte[] body, String signature) {
        return new InboundWebhook(signature == null ? Map.of() : Map.of("X-Signature", List.of(signature)), body);
    }

    // ------------------------------------------------------------------ configuration

    @Test
    void certificate_and_key_go_together() {
        Map<String, String> c = credentials("http://localhost:1");
        c.put("client-cert", "-----BEGIN CERTIFICATE-----\nAA==\n-----END CERTIFICATE-----");

        assertThatThrownBy(() -> new PiSpiFactory().create(c)).hasMessageContaining("go together");
    }

    @Test
    void missing_credentials_are_named() {
        Map<String, String> c = credentials("http://localhost:1");
        c.remove("merchant-alias");

        assertThatThrownBy(() -> new PiSpiFactory().create(c)).hasMessageContaining("merchant-alias");
    }
}
