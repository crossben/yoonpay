package dev.yoonpay.server.provider;

import dev.yoonpay.core.provider.CallOutcome;
import dev.yoonpay.core.provider.PaymentProvider;
import dev.yoonpay.core.provider.PaymentProviderFactory;
import dev.yoonpay.core.provider.ProviderId;
import dev.yoonpay.core.routing.Candidate;
import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.config.YoonProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The providers each application has configured, built from that application's credentials,
 * each behind its own circuit breaker. An open breaker removes the provider from routing and
 * turns calls into a definite "not sent" rejection — the provider is never contacted.
 */
@Component
public class ProviderRegistry {

    /** Code for "provably not sent": the only rejection that says nothing about the customer. */
    public static final String PROVIDER_UNAVAILABLE = "PROVIDER_UNAVAILABLE";

    private final Map<String, PaymentProviderFactory> factories;
    private final YoonProperties properties;
    private final CircuitBreakerRegistry breakers;
    private final Map<String, PaymentProvider> instances = new ConcurrentHashMap<>();

    public ProviderRegistry(List<PaymentProviderFactory> factories, YoonProperties properties) {
        this.factories = factories.stream().collect(Collectors.toMap(f -> f.id().value(), f -> f));
        this.properties = properties;
        this.breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build());
    }

    /** Configured providers with a known factory, for routing. */
    public List<Candidate> candidates(AppPrincipal app) {
        return properties.app(app.name()).providers().entrySet().stream()
                .filter(e -> factories.containsKey(e.getKey()))
                .map(e -> new Candidate(new ProviderId(e.getKey()), e.getValue().priority(),
                        provider(app, e.getKey()).capabilities(), available(app, e.getKey())))
                .toList();
    }

    public Optional<PaymentProvider> find(AppPrincipal app, String providerId) {
        if (!factories.containsKey(providerId) || !properties.app(app.name()).providers().containsKey(providerId)) {
            return Optional.empty();
        }
        return Optional.of(provider(app, providerId));
    }

    public boolean available(AppPrincipal app, String providerId) {
        CircuitBreaker.State state = breaker(app, providerId).getState();
        return state != CircuitBreaker.State.OPEN && state != CircuitBreaker.State.FORCED_OPEN;
    }

    /**
     * Runs a mutating call through the provider's breaker. Unknown outcomes and "not sent"
     * rejections count as failures; a business rejection means the provider is healthy.
     */
    public CallOutcome call(AppPrincipal app, String providerId, Supplier<CallOutcome> call) {
        CircuitBreaker breaker = breaker(app, providerId);
        if (!breaker.tryAcquirePermission()) {
            return new CallOutcome.Rejected(PROVIDER_UNAVAILABLE, "circuit open: provider not contacted");
        }
        long start = System.nanoTime();
        CallOutcome outcome;
        try {
            outcome = call.get();
        } catch (RuntimeException e) {
            // An adapter bug after the request may have left: treat as unknown, never as rejected.
            breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, e);
            return new CallOutcome.Unknown("adapter error: " + e.getClass().getSimpleName());
        }
        long took = System.nanoTime() - start;
        boolean unhealthy = outcome instanceof CallOutcome.Unknown
                || (outcome instanceof CallOutcome.Rejected r && r.code().equals(PROVIDER_UNAVAILABLE));
        if (unhealthy) {
            breaker.onError(took, TimeUnit.NANOSECONDS, new ProviderUnhealthy(outcome.toString()));
        } else {
            breaker.onSuccess(took, TimeUnit.NANOSECONDS);
        }
        return outcome;
    }

    private PaymentProvider provider(AppPrincipal app, String providerId) {
        return instances.computeIfAbsent(app.id() + "/" + providerId, k -> factories.get(providerId)
                .create(properties.app(app.name()).providers().get(providerId).credentials()));
    }

    private CircuitBreaker breaker(AppPrincipal app, String providerId) {
        return breakers.circuitBreaker(app.id() + "/" + providerId);
    }

    /** Test hook: force a provider's breaker open or closed. */
    public void forceState(AppPrincipal app, String providerId, CircuitBreaker.State state) {
        CircuitBreaker b = breaker(app, providerId);
        switch (state) {
            case OPEN -> b.transitionToForcedOpenState();
            default -> b.reset();
        }
    }

    private static final class ProviderUnhealthy extends RuntimeException {
        ProviderUnhealthy(String message) {
            super(message, null, false, false);
        }
    }
}
