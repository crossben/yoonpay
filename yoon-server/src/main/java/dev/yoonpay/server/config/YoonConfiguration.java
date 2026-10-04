package dev.yoonpay.server.config;

import dev.yoonpay.core.routing.Router;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties({YoonProperties.class, SweepProperties.class, CheckoutProperties.class})
public class YoonConfiguration {

    @Bean
    Router router() {
        return new Router();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
