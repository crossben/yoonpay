package dev.yoonpay.server.web;

import org.springframework.http.HttpStatus;

/**
 * An expected API error, rendered as RFC 9457 problem+json. {@code code} is stable and
 * machine-readable; it becomes the problem {@code type} and a {@code code} member.
 */
public class ApiProblem extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final java.util.Map<String, Object> properties = new java.util.LinkedHashMap<>();

    public ApiProblem(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** Extra machine-readable members of the problem (e.g. {@code reason}). */
    public ApiProblem with(String name, Object value) {
        properties.put(name, value);
        return this;
    }

    public java.util.Map<String, Object> properties() {
        return java.util.Collections.unmodifiableMap(properties);
    }

    public static ApiProblem notFound(String what, String id) {
        return new ApiProblem(HttpStatus.NOT_FOUND, "resource_not_found", what + " " + id + " not found");
    }

    public static ApiProblem invalid(String message) {
        return new ApiProblem(HttpStatus.BAD_REQUEST, "invalid_request", message);
    }

    public static ApiProblem unprocessable(String code, String message) {
        return new ApiProblem(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }
}
