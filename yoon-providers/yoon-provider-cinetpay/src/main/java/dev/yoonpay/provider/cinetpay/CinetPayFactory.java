package dev.yoonpay.provider.cinetpay;

import dev.yoonpay.core.provider.PaymentProvider;
import dev.yoonpay.core.provider.PaymentProviderFactory;
import dev.yoonpay.core.provider.ProviderId;
import dev.yoonpay.provider.support.Credentials;
import dev.yoonpay.provider.support.ProviderHttp;

import java.util.Map;

public final class CinetPayFactory implements PaymentProviderFactory {

    private final ProviderHttp http;

    public CinetPayFactory() {
        this(ProviderHttp.withDefaults());
    }

    public CinetPayFactory(ProviderHttp http) {
        this.http = http;
    }

    @Override
    public ProviderId id() {
        return CinetPayProvider.ID;
    }

    @Override
    public PaymentProvider create(Map<String, String> credentials) {
        return new CinetPayProvider(new Credentials("cinetpay", credentials), http);
    }
}
