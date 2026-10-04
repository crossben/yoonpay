package dev.yoonpay.provider.stripe;

import dev.yoonpay.core.provider.PaymentProvider;
import dev.yoonpay.core.provider.PaymentProviderFactory;
import dev.yoonpay.core.provider.ProviderId;
import dev.yoonpay.provider.support.Credentials;
import dev.yoonpay.provider.support.ProviderHttp;

import java.util.Map;

public final class StripeFactory implements PaymentProviderFactory {

    private final ProviderHttp http;

    public StripeFactory() {
        this(ProviderHttp.withDefaults());
    }

    public StripeFactory(ProviderHttp http) {
        this.http = http;
    }

    @Override
    public ProviderId id() {
        return StripeProvider.ID;
    }

    @Override
    public PaymentProvider create(Map<String, String> credentials) {
        return new StripeProvider(new Credentials("stripe", credentials), http);
    }
}
