package dev.yoonpay.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.yoonpay.client.generated.ApiException;

/**
 * An error answered by Yoon (application/problem+json), or a transport failure.
 * {@link #problemCode()} is Yoon's stable code, e.g. {@code no_provider_for_method}; null when
 * Yoon could not be reached — then retry with the same idempotency key; {@code unreadable_response}
 * when Yoon answered something this client could not read.
 */
public final class YoonException extends RuntimeException {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final int httpStatus;
    private final String problemCode;

    YoonException(String message, int httpStatus, String problemCode, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.problemCode = problemCode;
    }

    static YoonException from(ApiException e) {
        if (e.getCode() == 0) {
            boolean transport = e.getCause() instanceof java.io.IOException
                    && !(e.getCause() instanceof com.fasterxml.jackson.core.JacksonException);
            return transport
                    ? new YoonException("Yoon unreachable: " + e.getMessage(), 0, null, e)
                    : new YoonException("Unreadable answer from Yoon: " + e.getMessage(), 0, "unreadable_response", e);
        }
        String code = null;
        String detail = e.getMessage();
        try {
            JsonNode p = JSON.readTree(e.getResponseBody() == null ? "{}" : e.getResponseBody());
            code = p.path("code").isTextual() ? p.path("code").asText() : null;
            detail = p.path("detail").isTextual() ? p.path("detail").asText() : detail;
        } catch (Exception ignored) {
            // not a problem document
        }
        return new YoonException(detail, e.getCode(), code, e);
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String problemCode() {
        return problemCode;
    }

    /** True when retrying with the same idempotency key is the right move (Yoon unreachable, busy or failing). */
    public boolean isRetryable() {
        return problemCode == null || "idempotency_in_progress".equals(problemCode) || httpStatus >= 500;
    }

    static YoonException transport(String message, Throwable cause) {
        return new YoonException(message, 0, null, cause);
    }

    static YoonException http(int status, String body) {
        return from(new ApiException(status, "HTTP " + status, null, body));
    }
}
