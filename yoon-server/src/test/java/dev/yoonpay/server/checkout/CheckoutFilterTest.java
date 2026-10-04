package dev.yoonpay.server.checkout;

import dev.yoonpay.server.config.CheckoutProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** Headers on every checkout path, and the per-address limit. */
class CheckoutFilterTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-04T10:00:30Z"), ZoneOffset.UTC);

    private static MockHttpServletResponse call(CheckoutFilter filter, String path, String address) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr(address);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        if (chain.getRequest() != null) {
            response.setStatus(200);
        }
        return response;
    }

    @Test
    void every_checkout_path_gets_the_strict_headers_and_other_paths_none() throws Exception {
        CheckoutFilter filter = new CheckoutFilter(new CheckoutProperties(Duration.ofMinutes(30), 0), CLOCK);
        for (String path : new String[]{"/checkout/pay_1", "/checkout/checkout.js", "/checkout/api/pay_1"}) {
            assertThat(call(filter, path, "10.0.0.1").getHeader("Content-Security-Policy")).as(path).isEqualTo(CheckoutFilter.CSP);
        }
        assertThat(call(filter, "/checkouts", "10.0.0.1").getHeader("Content-Security-Policy")).isNull();
        assertThat(call(filter, "/v1/payments", "10.0.0.1").getHeader("Content-Security-Policy")).isNull();
    }

    @Test
    void an_address_over_the_limit_gets_429_until_the_next_minute() throws Exception {
        CheckoutFilter filter = new CheckoutFilter(new CheckoutProperties(Duration.ofMinutes(30), 3), CLOCK);
        for (int i = 0; i < 3; i++) {
            assertThat(call(filter, "/checkout/api/pay_1", "10.0.0.1").getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse limited = call(filter, "/checkout/api/pay_1", "10.0.0.1");
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getContentAsString()).contains("\"code\":\"rate_limited\"");
        assertThat(limited.getHeader("Retry-After")).isEqualTo("30");
        assertThat(limited.getHeader("Content-Security-Policy")).isEqualTo(CheckoutFilter.CSP);
        assertThat(call(filter, "/checkout/api/pay_1", "10.0.0.2").getStatus()).isEqualTo(200);
    }

    @Test
    void the_limit_resets_each_minute_and_zero_means_off() {
        var now = new java.util.concurrent.atomic.AtomicLong(Instant.parse("2026-10-04T10:00:00Z").toEpochMilli());
        Clock moving = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId zone) { return this; }
            public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        };
        var limiter = new CheckoutFilter.Limiter(1, moving);
        assertThat(limiter.allow("a")).isTrue();
        assertThat(limiter.allow("a")).isFalse();
        now.addAndGet(60_000);
        assertThat(limiter.allow("a")).isTrue();

        var off = new CheckoutFilter.Limiter(0, moving);
        for (int i = 0; i < 1000; i++) {
            assertThat(off.allow("a")).isTrue();
        }
    }
}
