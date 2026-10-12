package dev.yoonpay.server.provider;

import dev.yoonpay.provider.dexpay.DexPayFactory;
import dev.yoonpay.server.config.YoonProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A misconfigured provider stops Yoon at startup, with every problem listed and no secret printed. */
class ProviderConfigValidationTest {

    private static YoonProperties config(Map<String, YoonProperties.Provider> providers) {
        return new YoonProperties(Map.of("shop", new YoonProperties.App(providers, null, null)), null, null);
    }

    @Test
    void a_missing_credential_and_an_unknown_provider_stop_startup_with_one_message() {
        var providers = Map.of(
                "dexpay", new YoonProperties.Provider(1, Map.of("api-secret", "do-not-print-me")),
                "paydunyaa", new YoonProperties.Provider(2, Map.of()));

        assertThatThrownBy(() -> new ProviderRegistry(List.of(new DexPayFactory()), config(providers), new SimpleMeterRegistry()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("application 'shop': dexpay: missing credential 'api-key'")
                .hasMessageContaining("unknown provider 'paydunyaa'")
                .message().doesNotContain("do-not-print-me");
    }

    @Test
    void a_complete_configuration_starts() {
        var providers = Map.of("dexpay", new YoonProperties.Provider(1, Map.of("api-key", "k", "api-secret", "s")));

        ProviderRegistry registry = new ProviderRegistry(List.of(new DexPayFactory()), config(providers), new SimpleMeterRegistry());

        assertThat(registry).isNotNull();
    }
}
