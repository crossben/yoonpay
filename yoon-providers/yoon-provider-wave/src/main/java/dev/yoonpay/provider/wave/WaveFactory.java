package dev.yoonpay.provider.wave;

import dev.yoonpay.core.provider.PaymentProvider;
import dev.yoonpay.core.provider.PaymentProviderFactory;
import dev.yoonpay.core.provider.ProviderId;
import dev.yoonpay.provider.support.Credentials;
import dev.yoonpay.provider.support.ProviderHttp;

import java.util.Map;

public final class WaveFactory implements PaymentProviderFactory {

    private final ProviderHttp http;

    public WaveFactory() {
        this(ProviderHttp.withDefaults());
    }

    public WaveFactory(ProviderHttp http) {
        this.http = http;
    }

    @Override
    public ProviderId id() {
        return WaveProvider.ID;
    }

    @Override
    public PaymentProvider create(Map<String, String> credentials) {
        return new WaveProvider(new Credentials("wave", credentials), http);
    }
}
