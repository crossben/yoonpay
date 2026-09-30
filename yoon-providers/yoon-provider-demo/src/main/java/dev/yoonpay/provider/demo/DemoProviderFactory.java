package dev.yoonpay.provider.demo;

import dev.yoonpay.core.provider.PaymentProvider;
import dev.yoonpay.core.provider.PaymentProviderFactory;
import dev.yoonpay.core.provider.ProviderId;
import dev.yoonpay.provider.support.Credentials;

import java.net.URI;
import java.util.Map;

/**
 * One demo provider per application. Optional credential {@code secret} (callback signing);
 * a per-instance random one is used otherwise. {@code checkoutBase} is Yoon's public address.
 */
public final class DemoProviderFactory implements PaymentProviderFactory {

    private final DemoBank bank;
    private final URI checkoutBase;
    private final String defaultSecret = java.util.UUID.randomUUID().toString();

    public DemoProviderFactory(DemoBank bank, URI checkoutBase) {
        this.bank = bank;
        this.checkoutBase = checkoutBase;
    }

    @Override
    public ProviderId id() {
        return DemoProvider.ID;
    }

    @Override
    public PaymentProvider create(Map<String, String> credentials) {
        return new DemoProvider(bank, checkoutBase, new Credentials("demo", credentials).optional("secret", defaultSecret));
    }

    public DemoBank bank() {
        return bank;
    }

}
