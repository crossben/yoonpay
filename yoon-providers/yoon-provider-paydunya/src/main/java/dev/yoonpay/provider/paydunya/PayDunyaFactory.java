package dev.yoonpay.provider.paydunya;

import dev.yoonpay.core.provider.PaymentProvider;
import dev.yoonpay.core.provider.PaymentProviderFactory;
import dev.yoonpay.core.provider.ProviderId;
import dev.yoonpay.provider.support.Credentials;
import dev.yoonpay.provider.support.ProviderHttp;

import java.util.Map;

public final class PayDunyaFactory implements PaymentProviderFactory {

    private final ProviderHttp http;

    public PayDunyaFactory() {
        this(ProviderHttp.withDefaults());
    }

    public PayDunyaFactory(ProviderHttp http) {
        this.http = http;
    }

    @Override
    public ProviderId id() {
        return PayDunyaProvider.ID;
    }

    @Override
    public PaymentProvider create(Map<String, String> credentials) {
        return new PayDunyaProvider(new Credentials("paydunya", credentials), http);
    }
}
