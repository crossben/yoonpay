package dev.yoonpay.provider.pispi;

import dev.yoonpay.core.provider.PaymentProvider;
import dev.yoonpay.core.provider.PaymentProviderFactory;
import dev.yoonpay.core.provider.ProviderId;
import dev.yoonpay.provider.support.Credentials;
import dev.yoonpay.provider.support.ProviderHttp;
import dev.yoonpay.provider.support.Tls;

import java.util.Map;

/**
 * Each application brings its own BCEAO client certificate, so each gets its own HTTP client when
 * a certificate is configured.
 */
public final class PiSpiFactory implements PaymentProviderFactory {

    private final ProviderHttp http;

    public PiSpiFactory() {
        this(ProviderHttp.withDefaults());
    }

    public PiSpiFactory(ProviderHttp http) {
        this.http = http;
    }

    @Override
    public ProviderId id() {
        return PiSpiProvider.ID;
    }

    @Override
    public PaymentProvider create(Map<String, String> credentials) {
        Credentials c = new Credentials("pispi", credentials);
        String cert = Tls.pemOrFile(c.optional("client-cert", null));
        String key = Tls.pemOrFile(c.optional("client-key", null));
        if ((cert == null) != (key == null)) {
            throw new IllegalArgumentException("pispi: 'client-cert' and 'client-key' go together");
        }
        ProviderHttp client = cert == null ? http
                : http.withSsl(Tls.fromPem(key, cert, Tls.pemOrFile(c.optional("ca-cert", null))));
        return new PiSpiProvider(c, client);
    }
}
