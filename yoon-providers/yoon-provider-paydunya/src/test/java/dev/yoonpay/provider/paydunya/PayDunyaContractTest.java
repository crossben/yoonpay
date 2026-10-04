package dev.yoonpay.provider.paydunya;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.yoonpay.core.provider.PaymentProvider;
import dev.yoonpay.provider.support.ProviderHttp;
import dev.yoonpay.testkit.ProviderContract;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/** The shared money-safety rules ({@link ProviderContract}), run against this adapter. */
class PayDunyaContractTest extends ProviderContract {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    /** Requests that create a payment, payout or refund. */
    private static final List<String> MONEY_MOVING = List.of("/checkout-invoice/create", "/disburse/submit-invoice");

    private static Map<String, String> credentials(String base) {
        Map<String, String> m = new HashMap<>();
        m.put("master-key", "mk");
        m.put("private-key", "pk");
        m.put("token", "tk");
        m.put("base-url", base + "/api/v1");
        m.put("disburse-base-url", base + "/api/v2");
        return m;
    }

    @Override
    protected PaymentProvider provider() {
        return new PayDunyaFactory(ProviderHttp.withDefaults()).create(credentials("http://localhost:" + wm.getPort()));
    }

    @Override
    protected PaymentProvider providerWithShortTimeout() {
        return new PayDunyaFactory(new ProviderHttp(Duration.ofSeconds(1), Duration.ofMillis(300)))
                .create(credentials("http://localhost:" + wm.getPort()));
    }

    @Override
    protected PaymentProvider unreachableProvider() {
        return new PayDunyaFactory(ProviderHttp.withDefaults()).create(credentials("http://localhost:1"));
    }

    @Override
    protected void answerEverything(int status) {
        wm.stubFor(any(anyUrl()).atPriority(10).willReturn(jsonResponse("{}", status)));
    }

    @Override
    protected void dropEverything() {
        wm.stubFor(any(anyUrl()).atPriority(10).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
    }

    @Override
    protected void hangEverything() {
        wm.stubFor(any(anyUrl()).atPriority(10).willReturn(okJson("{}").withFixedDelay(1500)));
    }

    @Override
    protected int moneyMovingRequestsReceived() {
        return (int) wm.getAllServeEvents().stream()
                .filter(e -> !e.getRequest().getMethod().getName().equals("GET"))
                .filter(e -> MONEY_MOVING.stream().anyMatch(p -> e.getRequest().getUrl().contains(p)))
                .count();
    }
}
