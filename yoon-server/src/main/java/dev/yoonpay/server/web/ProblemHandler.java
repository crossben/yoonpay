package dev.yoonpay.server.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.stream.Collectors;

/** Every error leaves as problem+json with a stable {@code code}. */
@RestControllerAdvice
public class ProblemHandler {

    public static final String TYPE_BASE = "https://yoonpay.dev/problems/";
    private static final Logger log = LoggerFactory.getLogger(ProblemHandler.class);

    @ExceptionHandler(ApiProblem.class)
    ResponseEntity<ProblemDetail> api(ApiProblem e) {
        return problem(e);
    }

    /** An expected error with its extra members ({@code reason}…); also what idempotent calls store and replay. */
    public static ResponseEntity<ProblemDetail> problem(ApiProblem e) {
        ResponseEntity<ProblemDetail> r = problem(e.status(), e.code(), e.getMessage());
        e.properties().forEach(r.getBody()::setProperty);
        return r;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, "invalid_request", detail);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> unreadable(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid_request", "Malformed request body or parameter");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ProblemDetail> missingHeader(MissingRequestHeaderException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid_request", "Missing header " + e.getHeaderName());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> noRoute(NoResourceFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "resource_not_found", "No such endpoint");
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> wrongMethod(org.springframework.web.HttpRequestMethodNotSupportedException e) {
        // e.g. a provider dashboard (or a browser) opening a webhook URL with GET
        ResponseEntity<ProblemDetail> r = problem(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed",
                "Method " + e.getMethod() + " is not allowed here");
        return e.getSupportedHttpMethods() == null ? r
                : ResponseEntity.status(r.getStatusCode()).allow(e.getSupportedHttpMethods().toArray(org.springframework.http.HttpMethod[]::new))
                        .body(r.getBody());
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> wrongMediaType(org.springframework.web.HttpMediaTypeNotSupportedException e) {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type", "Send application/json");
    }

    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    ResponseEntity<ProblemDetail> missingParameter(org.springframework.web.bind.MissingServletRequestParameterException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid_request", "Missing parameter " + e.getParameterName());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        log.error("Unhandled error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Internal error");
    }

    public static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create(TYPE_BASE + code));
        p.setTitle(status.getReasonPhrase());
        p.setProperty("code", code);
        return ResponseEntity.status(status).body(p);
    }
}
