package dev.yoonpay.server.dashboard;

import dev.yoonpay.server.web.ProblemHandler;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Guards {@code /dashboard} and its static assets (ADR-0021). The page exists only when
 * {@code YOON_DASHBOARD_ENABLED} is true and the operator API is on ({@code YOON_ADMIN_TOKEN} of at
 * least 32 characters); otherwise 404. Every dashboard response gets a strict, same-origin-only
 * Content-Security-Policy and is never cached or framed. The page itself holds no secret: all data
 * comes from {@code /admin/v1} with the token the operator types in.
 */
@Component
public class DashboardFilter extends OncePerRequestFilter {

    /** No inline script or style, no third-party origin, no framing, no forms posting anywhere. */
    public static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; "
            + "connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

    private final boolean enabled;

    public DashboardFilter(@Value("${yoon.dashboard.enabled:true}") boolean enabled,
                           @Value("${yoon.admin-token:}") String adminToken) {
        this.enabled = enabled && adminToken.length() >= 32;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !(uri.equals("/dashboard") || uri.startsWith("/dashboard/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!enabled) {
            response.setStatus(404);
            response.setContentType("application/problem+json");
            response.getWriter().write("""
                    {"type":"%sresource_not_found","title":"Not Found","status":404,"detail":"The dashboard is disabled (YOON_DASHBOARD_ENABLED, YOON_ADMIN_TOKEN)","code":"resource_not_found"}"""
                    .formatted(ProblemHandler.TYPE_BASE));
            return;
        }
        response.setHeader("Content-Security-Policy", CSP);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }
}
