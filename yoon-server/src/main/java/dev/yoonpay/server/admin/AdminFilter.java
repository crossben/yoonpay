package dev.yoonpay.server.admin;

import dev.yoonpay.server.web.ProblemHandler;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Guards {@code /admin/v1/**} with {@code Authorization: Bearer <YOON_ADMIN_TOKEN>}. Without a
 * token of at least 32 characters configured, the admin API does not exist (404).
 */
@Component
public class AdminFilter extends OncePerRequestFilter {

    private final byte[] token;

    public AdminFilter(@Value("${yoon.admin-token:}") String token) {
        this.token = token.length() >= 32 ? token.getBytes(StandardCharsets.UTF_8) : null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (token == null) {
            write(response, 404, "resource_not_found", "Not Found", "The admin API is disabled (set YOON_ADMIN_TOKEN)");
            return;
        }
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        byte[] sent = header != null && header.startsWith("Bearer ")
                ? header.substring(7).trim().getBytes(StandardCharsets.UTF_8) : new byte[0];
        if (!MessageDigest.isEqual(token, sent)) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            write(response, 401, "unauthorized", "Unauthorized", "Missing or invalid admin token");
            return;
        }
        chain.doFilter(request, response);
    }

    private static void write(HttpServletResponse response, int status, String code, String title, String detail)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getWriter().write("""
                {"type":"%s%s","title":"%s","status":%d,"detail":"%s","code":"%s"}"""
                .formatted(ProblemHandler.TYPE_BASE, code, title, status, detail, code));
    }
}
