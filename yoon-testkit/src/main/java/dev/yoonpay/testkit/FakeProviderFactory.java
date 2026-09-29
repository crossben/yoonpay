package dev.yoonpay.testkit;

import dev.yoonpay.core.provider.PaymentProvider;
import dev.yoonpay.core.provider.PaymentProviderFactory;
import dev.yoonpay.core.provider.ProviderId;

import java.util.Map;

/** Hands the same {@link FakeProvider} to every application, so a test can script and inspect it. */
public final class FakeProviderFactory implements PaymentProviderFactory {

    private final FakeProvider provider;

    public FakeProviderFactory(FakeProvider provider) {
        this.provider = provider;
    }

    @Override
    public ProviderId id() {
        return provider.id();
    }

    @Override
    public PaymentProvider create(Map<String, String> credentials) {
        return provider;
    }
}
