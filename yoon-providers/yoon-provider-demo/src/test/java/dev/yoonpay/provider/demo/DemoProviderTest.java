package dev.yoonpay.provider.demo;

import dev.yoonpay.core.lifecycle.PaymentStatus;
import dev.yoonpay.core.money.Money;
import dev.yoonpay.core.provider.CallOutcome;
import dev.yoonpay.core.provider.CollectRequest;
import dev.yoonpay.core.provider.InboundWebhook;
import dev.yoonpay.core.provider.ProviderReference;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DemoProviderTest {

    private final DemoBank bank = new DemoBank();
    private final DemoProvider provider = new DemoProvider(bank, URI.create("http://localhost:8080"), "demo-secret");

    private CallOutcome.Accepted open() {
        return (CallOutcome.Accepted) provider.collect(new CollectRequest("att_1", Money.of(5000, "XOF"), "SN", "wave", null,
                "Order", URI.create("http://shop/return"), URI.create("http://yoon/v1/hooks/demo/app")));
    }

    @Test
    void a_checkout_opens_on_yoons_demo_page_and_waits_for_the_visitor() {
        var accepted = open();

        assertThat(accepted.checkoutUrl()).isEqualTo(URI.create("http://localhost:8080/demo/checkout/att_1"));
        assertThat(provider.status(accepted.reference()).status()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void paying_confirms_the_amount_and_a_decision_is_final() {
        var accepted = open();

        bank.decide("att_1", true);
        bank.decide("att_1", false);

        var s = provider.status(accepted.reference());
        assertThat(s.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(s.confirmedAmount()).isEqualTo(Money.of(5000, "XOF"));
    }

    @Test
    void callbacks_are_signed_like_a_real_providers() {
        open();
        var paid = bank.decide("att_1", true).orElseThrow();
        byte[] body = provider.callbackBody(paid);

        var good = provider.verify(new InboundWebhook(Map.of(DemoProvider.SIGNATURE_HEADER, List.of(provider.sign(body))), body));
        var bad = provider.verify(new InboundWebhook(Map.of(DemoProvider.SIGNATURE_HEADER, List.of("00")), body));

        assertThat(good.signatureValid()).isTrue();
        assertThat(good.reference()).isEqualTo(new ProviderReference("att_1"));
        assertThat(bad.signatureValid()).isFalse();
    }
}
