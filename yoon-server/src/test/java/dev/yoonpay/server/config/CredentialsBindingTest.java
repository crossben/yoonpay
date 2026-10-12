package dev.yoonpay.server.config;

import dev.yoonpay.provider.support.Credentials;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provider credentials as operators set them: environment variables, exactly as the provider docs
 * spell them (found by the first sandbox run: DexPay saw no 'api-key').
 */
class CredentialsBindingTest {

    private static Credentials bind(Map<String, Object> env) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
        Binder binder = new Binder(ConfigurationPropertySources.get(environment));
        YoonProperties properties = binder.bind("yoon", YoonProperties.class).get();
        return new Credentials("dexpay", properties.app("shop").providers().get("dexpay").credentials());
    }

    @Test
    void multi_word_credential_names_from_environment_variables_are_found() {
        Credentials c = bind(Map.of(
                "YOON_APPS_SHOP_PROVIDERS_DEXPAY_PRIORITY", "1",
                "YOON_APPS_SHOP_PROVIDERS_DEXPAY_CREDENTIALS_API_KEY", "k",
                "YOON_APPS_SHOP_PROVIDERS_DEXPAY_CREDENTIALS_API_SECRET", "s",
                "YOON_APPS_SHOP_PROVIDERS_DEXPAY_CREDENTIALS_PAYOUT_OPERATOR_WAVE", "wave_sn_payout"));

        assertThat(c.require("api-key")).isEqualTo("k");
        assertThat(c.require("api-secret")).isEqualTo("s");
        assertThat(c.optional("payout-operator-wave", null)).isEqualTo("wave_sn_payout");
    }
}
