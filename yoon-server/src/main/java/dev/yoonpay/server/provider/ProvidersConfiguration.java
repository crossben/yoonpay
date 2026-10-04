package dev.yoonpay.server.provider;

import dev.yoonpay.provider.cinetpay.CinetPayFactory;
import dev.yoonpay.provider.dexpay.DexPayFactory;
import dev.yoonpay.provider.naboopay.NabooPayFactory;
import dev.yoonpay.provider.paydunya.PayDunyaFactory;
import dev.yoonpay.provider.pispi.PiSpiFactory;
import dev.yoonpay.provider.stripe.StripeFactory;
import dev.yoonpay.provider.wave.WaveFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The provider modules shipped with Yoon. A provider is used by an application only once that
 * application configures it ({@code YOON_APPS_<APP>_PROVIDERS_<PROVIDER>_…}).
 */
@Configuration
public class ProvidersConfiguration {

    @Bean
    PayDunyaFactory paydunya() {
        return new PayDunyaFactory();
    }

    @Bean
    DexPayFactory dexpay() {
        return new DexPayFactory();
    }

    @Bean
    NabooPayFactory naboopay() {
        return new NabooPayFactory();
    }

    @Bean
    PiSpiFactory pispi() {
        return new PiSpiFactory();
    }

    @Bean
    WaveFactory wave() {
        return new WaveFactory();
    }

    @Bean
    StripeFactory stripe() {
        return new StripeFactory();
    }

    @Bean
    CinetPayFactory cinetpay() {
        return new CinetPayFactory();
    }
}
