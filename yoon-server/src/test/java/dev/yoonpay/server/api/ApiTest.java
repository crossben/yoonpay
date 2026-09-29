package dev.yoonpay.server.api;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import dev.yoonpay.server.PostgresTest;
import dev.yoonpay.server.TestProviders;
import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.auth.ApiKeyService;
import dev.yoonpay.server.provider.ProviderRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the real server over HTTP. Every response is validated against api/openapi.yaml, so
 * a test fails as soon as the code and the published contract disagree.
 */
public abstract class ApiTest extends PostgresTest {

    static final OpenApiInteractionValidator CONTRACT = OpenApiInteractionValidator
            .createForSpecificationUrl(Path.of("..", "api", "openapi.yaml").toAbsolutePath().toUri().toString())
            .build();

    private static final Map<String, AppPrincipal> APPS = new ConcurrentHashMap<>();
    private static final Map<String, String> KEYS = new ConcurrentHashMap<>();

    @LocalServerPort
    int port;

    @Autowired
    ApiKeyService apiKeys;

    @Autowired
    ProviderRegistry registry;

    @Autowired
    ObjectMapper json;

    private final HttpClient http = HttpClient.newHttpClient();

    public record Response(int status, Map<String, List<String>> headers, String body, JsonNode json) {
        public Optional<String> header(String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .flatMap(e -> e.getValue().stream()).findFirst();
        }

        public String text(String field) {
            JsonNode n = json.path(field);
            return n.isMissingNode() || n.isNull() ? null : n.asString();
        }
    }

    @BeforeEach
    void resetFakes() {
        RECEIVER.reset();
        for (var fake : List.of(TestProviders.FAKE_ONE, TestProviders.FAKE_TWO, TestProviders.FAKE_NO_REFUND)) {
            fake.reset();
            for (String app : List.of("shop", "other")) {
                registry.forceState(app(app), fake.id().value(), CircuitBreaker.State.CLOSED);
            }
        }
    }

    /** A named application ("shop", "other") created once, with an API key. */
    protected AppPrincipal app(String name) {
        return APPS.computeIfAbsent(name, n -> {
            var created = apiKeys.createApplication(n);
            KEYS.put(n, created.apiKey());
            return created.application();
        });
    }

    protected String key(String app) {
        app(app);
        return KEYS.get(app);
    }

    protected Response get(String app, String path) {
        return send(app, "GET", path, null, null);
    }

    protected Response post(String app, String path, Object body) {
        return send(app, "POST", path, body, UUID.randomUUID().toString());
    }

    protected Response post(String app, String path, Object body, String idempotencyKey) {
        return send(app, "POST", path, body, idempotencyKey);
    }

    /** POSTs raw bytes with the given headers and no API key — as a provider calls back. */
    protected Response postRaw(String path, java.util.Map<String, java.util.List<String>> headers, byte[] body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            headers.forEach((k, vs) -> vs.forEach(v -> b.header(k, v)));
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            String contentType = r.headers().firstValue("Content-Type").orElse("");
            JsonNode node = contentType.contains("json") && !r.body().isEmpty() ? json.readTree(r.body()) : null;
            return new Response(r.statusCode(), r.headers().map(), r.body(), node);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Calls the admin API with the test operator token. */
    protected Response admin(String method, String path, Object body) {
        return send("admin:" + ADMIN_TOKEN, method, path, body, null);
    }

    protected Response send(String app, String method, String path, Object body, String idempotencyKey) {
        try {
            String payload = body == null ? null : (body instanceof String s ? s : json.writeValueAsString(body));
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload));
            if (app != null) {
                String token = app.startsWith("admin:") ? app.substring(6)
                        : app.startsWith("yk_") || app.equals("bogus") ? app : key(app);
                b.header("Authorization", "Bearer " + token);
            }
            if (payload != null) {
                b.header("Content-Type", "application/json");
            }
            if (idempotencyKey != null) {
                b.header("Idempotency-Key", idempotencyKey);
            }
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            validate(method, path, payload, idempotencyKey, app != null, r);
            String contentType = r.headers().firstValue("Content-Type").orElse("");
            JsonNode node = contentType.contains("json") && !r.body().isEmpty() ? json.readTree(r.body()) : null;
            return new Response(r.statusCode(), r.headers().map(), r.body(), node);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void validate(String method, String path, String payload, String idempotencyKey, boolean auth,
                          HttpResponse<String> r) {
        URI uri = URI.create(path);
        SimpleRequest.Builder req = new SimpleRequest.Builder(Request.Method.valueOf(method), uri.getPath());
        if (uri.getQuery() != null) {
            for (String pair : uri.getQuery().split("&")) {
                String[] kv = pair.split("=", 2);
                req.withQueryParam(kv[0], kv.length > 1 ? java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8) : "");
            }
        }
        if (payload != null) {
            req.withBody(payload).withContentType("application/json");
        }
        if (idempotencyKey != null) {
            req.withHeader("Idempotency-Key", idempotencyKey);
        }
        if (auth) {
            req.withAuthorization("Bearer x");
        }
        SimpleResponse.Builder res = SimpleResponse.Builder.status(r.statusCode()).withBody(r.body());
        r.headers().map().forEach((k, v) -> res.withHeader(k, v));

        ValidationReport report = CONTRACT.validate(req.build(), res.build());
        // Request-side problems are expected in negative tests (we send bad input on purpose);
        // the response must always match the contract.
        List<ValidationReport.Message> responseErrors = report.getMessages().stream()
                .filter(m -> m.getLevel() == ValidationReport.Level.ERROR)
                .filter(m -> !m.getKey().startsWith("validation.request"))
                .toList();
        assertThat(responseErrors).as("%s %s -> %d must match api/openapi.yaml; body: %s",
                method, path, r.statusCode(), r.body()).isEmpty();
    }

    protected static Map<String, Object> payment(String method) {
        return Map.of("amount", 5000, "currency", "XOF", "country", "SN", "method", method,
                "customer", Map.of("phone", "+221771234567"), "reference", "order_" + UUID.randomUUID());
    }
}
