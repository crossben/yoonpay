package dev.yoonpay.spring;

import dev.yoonpay.client.Yoon;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * A {@link Yoon} bean when {@code yoon.api-key} is set, and the webhook filter when
 * {@code yoon.webhook.paths} is set. Any bean you declare yourself wins.
 */
@AutoConfiguration
@EnableConfigurationProperties(YoonProperties.class)
public class YoonAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "yoon", name = "api-key")
    public Yoon yoon(YoonProperties properties) {
        if (properties.getUrl() == null || properties.getUrl().isBlank()) {
            throw new IllegalStateException("Set yoon.url (YOON_URL), e.g. https://pay.example.com");
        }
        return new Yoon(properties.getUrl(), properties.getApiKey(), properties.getTimeout());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = {"jakarta.servlet.Filter", "org.springframework.web.filter.OncePerRequestFilter"})
    @Conditional(OnWebhookPaths.class)
    static class Webhooks {

        @Bean
        @ConditionalOnMissingBean
        YoonEventStore yoonEventStore(YoonProperties properties) {
            return new InMemoryYoonEventStore(properties.getWebhook().getDedupe());
        }

        @Bean
        @ConditionalOnMissingBean
        YoonWebhookFilter yoonWebhookFilter(YoonProperties properties, YoonEventStore store) {
            return new YoonWebhookFilter(properties.getWebhookSecret(), properties.getWebhook().getPaths(), store);
        }

        @Configuration(proxyBeanMethods = false)
        @ConditionalOnClass(name = "org.springframework.web.servlet.config.annotation.WebMvcConfigurer")
        static class Mvc {

            @Bean
            WebMvcConfigurer yoonEventArgument() {
                return new WebMvcConfigurer() {
                    @Override
                    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                        resolvers.add(new YoonEventArgumentResolver());
                    }
                };
            }
        }
    }
}
