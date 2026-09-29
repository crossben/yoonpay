package dev.yoonpay.server.web;

import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.idempotency.IdempotencyStore;
import dev.yoonpay.server.idempotency.IdempotencyStore.Claim;
import dev.yoonpay.server.idempotency.RequestHash;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * Wraps a mutating endpoint in the idempotency protocol: claim the key before doing anything,
 * store the response (success or expected error), replay it for identical retries.
 */
@Component
public class IdempotentCall {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final IdempotencyStore store;
    private final ObjectMapper json;

    public IdempotentCall(IdempotencyStore store, ObjectMapper json) {
        this.store = store;
        this.json = json;
    }

    public ResponseEntity<String> run(AppPrincipal app, String key, HttpServletRequest request, Object body,
                                      HttpStatus successStatus, Supplier<Object> action) {
        if (key == null || key.isBlank() || key.length() > 255) {
            throw ApiProblem.invalid(HEADER + " header is required (1 to 255 characters)");
        }
        String hash = RequestHash.of(request.getMethod(), request.getRequestURI(),
                json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));

        return switch (store.begin(app.id(), key, hash)) {
            case Claim.Started s -> execute(app, key, successStatus, action);
            case Claim.Replay r -> ResponseEntity.status(r.status())
                    .contentType(r.status() >= 400 ? MediaType.APPLICATION_PROBLEM_JSON : MediaType.APPLICATION_JSON)
                    .header(REPLAYED_HEADER, "true")
                    .body(r.body());
            case Claim.InProgress p -> problem(HttpStatus.CONFLICT, "idempotency_in_progress",
                    "A request with this Idempotency-Key is still being processed; retry shortly", true);
            case Claim.Mismatch m -> problem(HttpStatus.CONFLICT, "idempotency_key_reused",
                    "This Idempotency-Key was already used with a different request", false);
        };
    }

    private ResponseEntity<String> execute(AppPrincipal app, String key, HttpStatus successStatus, Supplier<Object> action) {
        int status;
        String body;
        MediaType type;
        try {
            body = json.writeValueAsString(action.get());
            status = successStatus.value();
            type = MediaType.APPLICATION_JSON;
        } catch (ApiProblem e) {
            // Expected errors are part of the response: an identical retry gets the same answer.
            body = json.writeValueAsString(ProblemHandler.problem(e.status(), e.code(), e.getMessage()).getBody());
            status = e.status().value();
            type = MediaType.APPLICATION_PROBLEM_JSON;
        }
        // Unexpected exceptions propagate and leave the key IN_PROGRESS: never re-executed blindly.
        store.complete(app.id(), key, status, body);
        return ResponseEntity.status(status).contentType(type).body(body);
    }

    private ResponseEntity<String> problem(HttpStatus status, String code, String detail, boolean retryAfter) {
        ProblemDetail p = ProblemHandler.problem(status, code, detail).getBody();
        var builder = ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (retryAfter) {
            builder.header(HttpHeaders.RETRY_AFTER, "1");
        }
        return builder.body(json.writeValueAsString(p));
    }
}
