package dev.yoonpay.provider.dexpay;

import dev.yoonpay.core.provider.PaymentProvider;
import dev.yoonpay.core.provider.PaymentProviderFactory;
import dev.yoonpay.core.provider.ProviderId;
import dev.yoonpay.provider.support.Credentials;
import dev.yoonpay.provider.support.ProviderHttp;

import java.util.Map;

public final class DexPayFactory implements PaymentProviderFactory {

    private final ProviderHttp http;

    public DexPayFactory() {
        this(ProviderHttp.withDefaults());
    }

    public DexPayFactory(ProviderHttp http) {
        this.http = http;
    }

    @Override
    public ProviderId id() {
        return DexPayProvider.ID;
    }

    @Override
    public PaymentProvider create(Map<String, String> credentials) {
        return new DexPayProvider(new Credentials("dexpay", credentials), http);
    }
}
