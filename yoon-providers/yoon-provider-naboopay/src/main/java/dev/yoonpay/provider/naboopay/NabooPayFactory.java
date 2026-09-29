package dev.yoonpay.provider.naboopay;

import dev.yoonpay.core.provider.PaymentProvider;
import dev.yoonpay.core.provider.PaymentProviderFactory;
import dev.yoonpay.core.provider.ProviderId;
import dev.yoonpay.provider.support.Credentials;
import dev.yoonpay.provider.support.ProviderHttp;

import java.util.Map;

public final class NabooPayFactory implements PaymentProviderFactory {

    private final ProviderHttp http;

    public NabooPayFactory() {
        this(ProviderHttp.withDefaults());
    }

    public NabooPayFactory(ProviderHttp http) {
        this.http = http;
    }

    @Override
    public ProviderId id() {
        return NabooPayProvider.ID;
    }

    @Override
    public PaymentProvider create(Map<String, String> credentials) {
        return new NabooPayProvider(new Credentials("naboopay", credentials), http);
    }
}
