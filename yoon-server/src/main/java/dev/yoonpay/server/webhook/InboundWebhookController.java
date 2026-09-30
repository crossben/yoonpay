package dev.yoonpay.server.webhook;

import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.auth.Applications;
import dev.yoonpay.server.provider.ProviderRegistry;
import dev.yoonpay.server.web.ApiProblem;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Provider callbacks. Authenticated by nothing here: the body is stored raw and answered
 * 200 at once; the worker verifies the signature and then re-confirms with the provider.
 * A callback is a hint to go and ask, never the truth.
 */
@RestController
public class InboundWebhookController {

    static final int MAX_BODY_BYTES = 64 * 1024;

    private final Applications applications;
    private final ProviderRegistry providers;
    private final InboundWebhookStore store;

    public InboundWebhookController(Applications applications, ProviderRegistry providers, InboundWebhookStore store) {
        this.applications = applications;
        this.providers = providers;
        this.store = store;
    }

    public record Received(boolean received) {
    }

    @PostMapping("/v1/hooks/{provider}/{applicationId}")
    Received receive(@PathVariable String provider, @PathVariable String applicationId, HttpServletRequest request)
            throws IOException {
        AppPrincipal app = parse(applicationId).flatMap(applications::find)
                .filter(a -> providers.find(a, provider).isPresent())
                .orElseThrow(() -> ApiProblem.notFound("Webhook endpoint", provider + "/" + applicationId));

        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            throw new ApiProblem(HttpStatus.CONTENT_TOO_LARGE, "payload_too_large", "Webhook body over 64 KiB");
        }

        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name.toLowerCase(), Collections.list(request.getHeaders(name)));
        }
        store.store(app, provider, headers, body);
        return new Received(true);
    }

    private static java.util.Optional<UUID> parse(String id) {
        try {
            return java.util.Optional.of(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
    }
}
