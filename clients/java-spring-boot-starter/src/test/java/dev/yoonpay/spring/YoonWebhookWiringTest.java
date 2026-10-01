package dev.yoonpay.spring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The auto-configured beans, wired into Spring MVC the way an application gets them. */
class YoonWebhookWiringTest {

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class App {
        @Bean
        YoonWebhookFilterTest.Webhooks webhooks() {
            return new YoonWebhookFilterTest.Webhooks();
        }
    }

    @Test
    void theAutoConfiguredFilterAndArgumentResolverServeAController() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(YoonAutoConfiguration.class))
                .withUserConfiguration(App.class)
                .withPropertyValues("yoon.webhook-secret=spring-test-webhook-secret-0123456789", "yoon.webhook.paths=/yoon/webhook")
                .run(context -> {
                    MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                            .addFilters(context.getBean(YoonWebhookFilter.class)).build();
                    String body = YoonWebhookFilterTest.event("evt_wired", "pay_1");
                    String signature = YoonWebhookFilterTest.sign(body, Instant.now().getEpochSecond());

                    mvc.perform(post("/yoon/webhook").contentType("application/json").content(body)
                            .header("Yoon-Signature", signature)).andExpect(status().isNoContent());
                    mvc.perform(post("/yoon/webhook").contentType("application/json").content(body)
                            .header("Yoon-Signature", "t=1,v1=00")).andExpect(status().isUnauthorized());

                    assertThat(context.getBean(YoonWebhookFilterTest.Webhooks.class).reached).containsExactly("evt_wired");
                    assertThat(context.getBean(YoonEventStore.class).has("evt_wired")).isTrue();
                });
    }
}
