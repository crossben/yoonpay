package dev.yoonpay.core.routing;

import dev.yoonpay.core.provider.ProviderId;

import java.util.Comparator;
import java.util.List;

/**
 * Chooses which configured providers may handle a request, in order of preference:
 * configured → capable (country, method, currency, operation) → not circuit-open →
 * optional pin → priority, then id for a stable tie-break.
 *
 * <p>The router only orders candidates. Whether the next one may be tried is the caller's
 * decision and depends on the previous call's outcome: only a definite rejection allows it.
 */
public final class Router {

    public sealed interface Decision {
        record Route(List<ProviderId> providers, String reason) implements Decision {
            public Route {
                providers = List.copyOf(providers);
            }
        }

        record NoRoute(String code, String message) implements Decision {
        }
    }

    public static final String NO_PROVIDER_FOR_METHOD = "no_provider_for_method";
    public static final String ALL_PROVIDERS_UNAVAILABLE = "all_providers_unavailable";
    public static final String PROVIDER_NOT_CONFIGURED = "provider_not_configured";

    public Decision route(RouteRequest request, List<Candidate> configured) {
        List<Candidate> pool = configured;
        if (request.pinned() != null) {
            pool = configured.stream().filter(c -> c.id().equals(request.pinned())).toList();
            if (pool.isEmpty()) {
                return new Decision.NoRoute(PROVIDER_NOT_CONFIGURED,
                        "Provider " + request.pinned() + " is not configured for this application");
            }
        }

        List<Candidate> capable = pool.stream()
                .filter(c -> c.capabilities().supports(request.operation(), request.country(), request.method(), request.currency()))
                .toList();
        if (capable.isEmpty()) {
            return new Decision.NoRoute(NO_PROVIDER_FOR_METHOD,
                    "No configured provider supports " + describe(request));
        }

        List<Candidate> available = capable.stream()
                .filter(Candidate::available)
                .sorted(Comparator.comparingInt(Candidate::priority).thenComparing(c -> c.id().value()))
                .toList();
        if (available.isEmpty()) {
            return new Decision.NoRoute(ALL_PROVIDERS_UNAVAILABLE,
                    "Every provider supporting " + describe(request) + " is currently unavailable");
        }

        String reason = request.pinned() != null
                ? "pinned by application"
                : available.size() + " of " + capable.size() + " capable providers available; ordered by priority";
        return new Decision.Route(available.stream().map(Candidate::id).toList(), reason);
    }

    private static String describe(RouteRequest r) {
        return r.operation().name().toLowerCase() + " by " + r.method() + " in " + r.country() + " (" + r.currency() + ")";
    }
}
