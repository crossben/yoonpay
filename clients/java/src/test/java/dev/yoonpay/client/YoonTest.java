package dev.yoonpay.client;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.yoonpay.client.generated.model.CreatePaymentRequest;
import dev.yoonpay.client.generated.model.CreatePaymentRequestCustomer;
import dev.yoonpay.client.generated.model.Payment;
import dev.yoonpay.client.generated.model.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YoonTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private Yoon yoon() {
        return new Yoon("http://localhost:" + wm.getPort() + "/", "yk_test");
    }

    private static CreatePaymentRequest request() {
        return new CreatePaymentRequest().amount(5000L).currency("XOF").country("SN").method("wave")
                .customer(new CreatePaymentRequestCustomer().phone("+221771234567")).reference("order_1042");
    }

    @Test
    void create_payment_sends_the_key_and_reads_the_payment() {
        wm.stubFor(post("/v1/payments").willReturn(jsonResponse("""
                {"id":"pay_1","object":"payment","status":"pending","amount":5000,"amount_refunded":0,"currency":"XOF",
                 "country":"SN","method":"wave","reference":"order_1042","description":null,"customer":{"phone":"+22177***67"},
                 "provider":"paydunya","provider_reference":"tok_1","checkout_url":"https://app.paydunya.com/c/tok_1",
                 "instructions":null,"routing_reason":"r","failure":null,
                 "created_at":"2026-09-30T10:00:00Z","updated_at":"2026-09-30T10:00:00Z"}""", 201)));

        Payment p = yoon().createPayment(request(), "order-1042");

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(p.getAmount()).isEqualTo(5000L);
        assertThat(p.getFailure()).isNull();
        wm.verify(postRequestedFor(urlEqualTo("/v1/payments"))
                .withHeader("Authorization", equalTo("Bearer yk_test"))
                .withHeader("Idempotency-Key", equalTo("order-1042"))
                .withRequestBody(matchingJsonPath("$.amount", equalTo("5000")))
                .withRequestBody(matchingJsonPath("$.customer.phone", equalTo("+221771234567"))));
    }

    @Test
    void problems_become_yoon_exceptions_with_their_code() {
        wm.stubFor(post("/v1/payments").willReturn(jsonResponse("""
                {"type":"https://yoonpay.dev/problems/no_provider_for_method","title":"Unprocessable Content","status":422,
                 "detail":"No configured provider supports collect by wave in SN (XOF)","code":"no_provider_for_method"}""", 422)
                .withHeader("Content-Type", "application/problem+json")));

        assertThatThrownBy(() -> yoon().createPayment(request(), "k"))
                .isInstanceOfSatisfying(YoonException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(422);
                    assertThat(e.problemCode()).isEqualTo("no_provider_for_method");
                    assertThat(e.isRetryable()).isFalse();
                });
    }
}
