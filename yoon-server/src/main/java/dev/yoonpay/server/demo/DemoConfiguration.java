package dev.yoonpay.server.demo;

import dev.yoonpay.provider.demo.DemoBank;
import dev.yoonpay.provider.demo.DemoProviderFactory;
import dev.yoonpay.server.config.YoonProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

/**
 * Demo mode ({@code YOON_DEMO_ENABLED=true}): registers the {@code demo} provider and its
 * checkout page. For trying Yoon and running the example shop — never in production.
 */
@Configuration
@ConditionalOnProperty(name = "yoon.demo.enabled", havingValue = "true")
public class DemoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DemoConfiguration.class);

    @Bean
    DemoBank demoBank() {
        return new DemoBank();
    }

    @Bean
    DemoProviderFactory demoProviderFactory(DemoBank bank, YoonProperties properties) {
        URI base = properties.publicUrl() != null ? properties.publicUrl() : URI.create("http://localhost:8080");
        log.warn("DEMO MODE: the 'demo' provider is enabled. It moves no money. Do not use in production.");
        return new DemoProviderFactory(bank, base);
    }
}
