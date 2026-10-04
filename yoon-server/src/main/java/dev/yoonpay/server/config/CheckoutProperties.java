package dev.yoonpay.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Hosted checkout ({@code YOON_CHECKOUT_*}, ADR-0024).
 *
 * @param ttl       how long a hosted checkout waits for the customer's choice before it fails
 *                  with {@code checkout_expired}
 * @param rateLimit requests per minute per client address on the public checkout endpoints; 0 = off
 */
@ConfigurationProperties("yoon.checkout")
public record CheckoutProperties(Duration ttl, Integer rateLimit) {

    public CheckoutProperties {
        ttl = ttl == null ? Duration.ofMinutes(30) : ttl;
        rateLimit = rateLimit == null ? 300 : rateLimit;
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("YOON_CHECKOUT_TTL must be positive");
        }
        if (rateLimit < 0) {
            throw new IllegalArgumentException("YOON_CHECKOUT_RATE_LIMIT must be 0 (off) or positive");
        }
    }
}
