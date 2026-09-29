package dev.yoonpay.server.auth;

import dev.yoonpay.server.web.ProblemHandler;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

/**
 * Authenticates {@code /v1/**} with {@code Authorization: Bearer yk_…}. Public: {@code /v1/about}.
 * Inbound provider webhooks (later) authenticate by signature, not by key.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String ATTRIBUTE = AppPrincipal.class.getName();
    private static final Set<String> PUBLIC = Set.of("/v1/about");

    private final ApiKeyService keys;

    public ApiKeyFilter(ApiKeyService keys) {
        this.keys = keys;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/v1/") || PUBLIC.contains(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        Optional<AppPrincipal> app = header != null && header.startsWith("Bearer ")
                ? keys.authenticate(header.substring(7).trim())
                : Optional.empty();

        if (app.isEmpty()) {
            response.setStatus(401);
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            response.setContentType("application/problem+json");
            response.getWriter().write("""
                    {"type":"%sunauthorized","title":"Unauthorized","status":401,\
                    "detail":"Missing or invalid API key","code":"unauthorized"}"""
                    .formatted(ProblemHandler.TYPE_BASE));
            return;
        }
        request.setAttribute(ATTRIBUTE, app.get());
        chain.doFilter(request, response);
    }
}
