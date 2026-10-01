package dev.yoonpay.spring;

import dev.yoonpay.client.Yoon;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import static org.assertj.core.api.Assertions.assertThat;

class YoonAutoConfigurationTest {

    private final ApplicationContextRunner plain = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(YoonAutoConfiguration.class));
    private final WebApplicationContextRunner web = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(YoonAutoConfiguration.class));

    @Test
    void aConfiguredClientIsABean() {
        plain.withPropertyValues("yoon.url=https://pay.example.com/", "yoon.api-key=yk_test", "yoon.timeout=10s")
                .run(context -> {
                    assertThat(context).hasSingleBean(Yoon.class);
                    assertThat(context.getBean(YoonProperties.class).getTimeout()).hasSeconds(10);
                });
    }

    @Test
    void noApiKeyNoClient() {
        plain.withPropertyValues("yoon.url=https://pay.example.com")
                .run(context -> assertThat(context).doesNotHaveBean(Yoon.class));
    }

    @Test
    void anApiKeyWithoutUrlFailsAtStartup() {
        plain.withPropertyValues("yoon.api-key=yk_test")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("yoon.url"));
    }

    @Test
    void theApplicationsOwnClientWins() {
        Yoon own = new Yoon("https://own.example.com", "yk_own");
        plain.withPropertyValues("yoon.url=https://pay.example.com", "yoon.api-key=yk_test")
                .withBean(Yoon.class, () -> own)
                .run(context -> assertThat(context.getBean(Yoon.class)).isSameAs(own));
    }

    @Test
    void noWebhookPathsNoFilter() {
        web.withPropertyValues("yoon.webhook-secret=whsec_0123456789abcdef0123456789")
                .run(context -> assertThat(context).doesNotHaveBean(YoonWebhookFilter.class));
    }

    @Test
    void webhookPathsGiveTheFilterTheStoreAndTheArgumentResolver() {
        web.withPropertyValues("yoon.webhook-secret=whsec_0123456789abcdef0123456789", "yoon.webhook.paths=/yoon/webhook,/other")
                .run(context -> {
                    assertThat(context).hasSingleBean(YoonWebhookFilter.class);
                    assertThat(context).hasSingleBean(YoonEventStore.class);
                    assertThat(context).hasSingleBean(WebMvcConfigurer.class);
                    assertThat(context.getBean(YoonProperties.class).getWebhook().getPaths()).containsExactly("/yoon/webhook", "/other");
                });
    }

    @Test
    void anIndexedListOfPathsWorksToo() {
        web.withPropertyValues("yoon.webhook-secret=whsec_0123456789abcdef0123456789", "yoon.webhook.paths[0]=/yoon/webhook")
                .run(context -> assertThat(context).hasSingleBean(YoonWebhookFilter.class));
    }

    @Test
    void webhookPathsWithoutSecretFailAtStartup() {
        web.withPropertyValues("yoon.webhook.paths=/yoon/webhook")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("yoon.webhook-secret"));
    }

    @Test
    void theApplicationsOwnStoreWins() {
        YoonEventStore own = new InMemoryYoonEventStore(java.time.Duration.ofMinutes(1));
        web.withPropertyValues("yoon.webhook-secret=whsec_0123456789abcdef0123456789", "yoon.webhook.paths=/yoon/webhook")
                .withBean(YoonEventStore.class, () -> own)
                .run(context -> assertThat(context.getBean(YoonEventStore.class)).isSameAs(own));
    }

    @Test
    void notAWebApplicationNoFilter() {
        plain.withPropertyValues("yoon.webhook-secret=whsec_0123456789abcdef0123456789", "yoon.webhook.paths=/yoon/webhook")
                .run(context -> assertThat(context).doesNotHaveBean(YoonWebhookFilter.class));
    }

    @Test
    void propertiesNeverPrintSecrets() {
        plain.withPropertyValues("yoon.url=https://pay.example.com", "yoon.api-key=yk_secret_key", "yoon.webhook-secret=whsec_secret")
                .run(context -> assertThat(context.getBean(YoonProperties.class).toString())
                        .doesNotContain("yk_secret_key").doesNotContain("whsec_secret"));
    }
}
