package dev.yoonpay.server.api;

import dev.yoonpay.core.provider.Capability;
import dev.yoonpay.core.routing.Candidate;
import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.config.YoonProperties;
import dev.yoonpay.server.provider.ProviderRegistry;
import org.springframework.boot.info.BuildProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

@RestController
public class MetaController {

    private final ProviderRegistry providers;
    private final YoonProperties properties;
    private final ObjectProvider<BuildProperties> build;

    public MetaController(ProviderRegistry providers, YoonProperties properties, ObjectProvider<BuildProperties> build) {
        this.providers = providers;
        this.properties = properties;
        this.build = build;
    }

    public record About(String name, String version, String license, String sourceUrl) {
    }

    /** Public. Where to get this instance's source code (AGPL-3.0 §13). */
    @GetMapping("/v1/about")
    About about() {
        BuildProperties b = build.getIfAvailable();
        return new About("Yoon", b == null ? "dev" : b.getVersion(), "AGPL-3.0-only", properties.sourceUrl().toString());
    }

    public record CapabilityResponse(String operation, String country, String method, String currency) {
    }

    public record ProviderResponse(String id, int priority, boolean available, List<CapabilityResponse> capabilities) {
    }

    /** What this application can use: configured providers, their capabilities and circuit state. */
    @GetMapping("/v1/providers")
    List<ProviderResponse> providers(AppPrincipal app) {
        return providers.candidates(app).stream()
                .sorted(Comparator.comparingInt(Candidate::priority).thenComparing(c -> c.id().value()))
                .map(c -> new ProviderResponse(c.id().value(), c.priority(), c.available(),
                        c.capabilities().supported().stream()
                                .sorted(Comparator.comparing((Capability k) -> k.operation().name())
                                        .thenComparing(Capability::country).thenComparing(Capability::method)
                                        .thenComparing(k -> k.currency().getCurrencyCode()))
                                .map(k -> new CapabilityResponse(k.operation().name().toLowerCase(), k.country(),
                                        k.method(), k.currency().getCurrencyCode()))
                                .toList()))
                .toList();
    }
}
