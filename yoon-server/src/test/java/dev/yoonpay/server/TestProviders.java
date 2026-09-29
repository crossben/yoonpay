package dev.yoonpay.server;

import dev.yoonpay.core.provider.Capabilities;
import dev.yoonpay.core.provider.Capability;
import dev.yoonpay.core.provider.Operation;
import dev.yoonpay.testkit.FakeProvider;
import dev.yoonpay.testkit.FakeProviderFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.Currency;
import java.util.Set;

/**
 * Three fake providers: {@code fakeone} and {@code faketwo} can do everything in Senegal;
 * {@code fakenorefund} collects card payments only and has no refund API.
 */
@TestConfiguration
public class TestProviders {

    public static final FakeProvider FAKE_ONE = new FakeProvider("fakeone", "secret-one");
    public static final FakeProvider FAKE_TWO = new FakeProvider("faketwo", "secret-two");
    public static final FakeProvider FAKE_NO_REFUND = new FakeProvider("fakenorefund", "secret-three",
            new Capabilities(Set.of(new Capability(Operation.COLLECT, "SN", "card", Currency.getInstance("XOF")))));

    @Bean
    FakeProviderFactory fakeOne() {
        return new FakeProviderFactory(FAKE_ONE);
    }

    @Bean
    FakeProviderFactory fakeTwo() {
        return new FakeProviderFactory(FAKE_TWO);
    }

    @Bean
    FakeProviderFactory fakeNoRefund() {
        return new FakeProviderFactory(FAKE_NO_REFUND);
    }
}
