package dev.yoonpay.server.checkout;

import dev.yoonpay.server.config.CheckoutProperties;
import dev.yoonpay.server.web.ProblemHandler;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Guards the public hosted checkout ({@code /checkout/**}, ADR-0024): the same strict, same-origin
 * CSP and headers as the operator dashboard, never cached or framed, and a per-client-address
 * request limit ({@code YOON_CHECKOUT_RATE_LIMIT} per minute, 0 = off).
 */
@Component
public class CheckoutFilter extends OncePerRequestFilter {

    /** No inline script or style, no third-party origin, no framing, no forms posting anywhere. */
    public static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; "
            + "connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

    private final Limiter limiter;

    public CheckoutFilter(CheckoutProperties properties, Clock clock) {
        this.limiter = new Limiter(properties.rateLimit(), clock);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !(uri.equals("/checkout") || uri.startsWith("/checkout/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", CSP);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");
        response.setHeader("Cache-Control", "no-store");
        if (!limiter.allow(request.getRemoteAddr())) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(limiter.secondsToNextWindow()));
            response.setContentType("application/problem+json");
            response.getWriter().write("""
                    {"type":"%srate_limited","title":"Too Many Requests","status":429,"detail":"Too many checkout requests; retry shortly","code":"rate_limited"}"""
                    .formatted(ProblemHandler.TYPE_BASE));
            return;
        }
        chain.doFilter(request, response);
    }

    /** Fixed one-minute windows per client address, in memory (per instance). */
    static final class Limiter {
        private static final int MAX_TRACKED = 100_000;

        private final int perMinute;
        private final Clock clock;
        private final ConcurrentHashMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();
        private volatile long window;

        Limiter(int perMinute, Clock clock) {
            this.perMinute = perMinute;
            this.clock = clock;
        }

        boolean allow(String address) {
            if (perMinute == 0) {
                return true;
            }
            long now = clock.millis() / 60_000;
            if (now != window) {
                synchronized (this) {
                    if (now != window) {
                        counts.clear();
                        window = now;
                    }
                }
            }
            String key = address == null ? "" : address;
            AtomicInteger n = counts.get(key);
            if (n == null) {
                if (counts.size() >= MAX_TRACKED) {
                    return false; // bounded memory: under a flood of addresses, refuse new ones until the next window
                }
                n = counts.computeIfAbsent(key, k -> new AtomicInteger());
            }
            return n.incrementAndGet() <= perMinute;
        }

        long secondsToNextWindow() {
            return Math.max(1, 60 - (clock.millis() / 1000) % 60);
        }
    }
}
