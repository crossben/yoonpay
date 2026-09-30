package dev.yoonpay.provider.pispi;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.yoonpay.core.provider.PaymentProvider;
import dev.yoonpay.core.provider.ProviderReference;
import dev.yoonpay.provider.support.ProviderHttp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/** A server that demands a client certificate: the one from the credentials gets through, none does not. */
class MutualTlsTest {

    private static final Path TLS = Path.of("src/test/resources/tls");

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig()
            .dynamicHttpsPort().httpDisabled(true)
            .keystorePath(TLS.resolve("server.p12").toString()).keystoreType("PKCS12")
            .keystorePassword("changeit").keyManagerPassword("changeit")
            .needClientAuth(true)
            .trustStorePath(TLS.resolve("trust.p12").toString()).trustStoreType("PKCS12").trustStorePassword("changeit"))
            .build();

    private PaymentProvider provider(boolean withCertificate) {
        Map<String, String> c = PiSpiProviderTest.credentials("https://localhost:" + wm.getHttpsPort());
        c.put("ca-cert", TLS.resolve("server.crt").toString());
        if (withCertificate) {
            c.put("client-cert", TLS.resolve("client.crt").toString());
            c.put("client-key", TLS.resolve("client.key").toString());
        } else {
            // Trust the server but present no certificate.
            c.put("client-cert", TLS.resolve("server.crt").toString());
            c.put("client-key", TLS.resolve("server.key").toString());
        }
        return new PiSpiFactory(ProviderHttp.withDefaults()).create(c);
    }

    @Test
    void the_configured_client_certificate_is_presented() {
        wm.stubFor(post("/oauth/token").willReturn(okJson("{\"access_token\":\"tok\",\"expires_in\":60}")));
        wm.stubFor(get("/piz/v1/demandes-paiements/att_1").willReturn(okJson("{\"txId\":\"att_1\",\"statut\":\"ENVOYE\"}")));

        assertThat(provider(true).status(new ProviderReference("att_1")).rawStatus()).isEqualTo("ENVOYE");
    }

    @Test
    void an_untrusted_certificate_gets_no_answer() {
        assertThat(provider(false).status(new ProviderReference("att_1")).rawStatus()).isNull();
    }
}
