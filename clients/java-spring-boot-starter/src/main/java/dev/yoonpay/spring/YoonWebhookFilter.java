package dev.yoonpay.spring;

import dev.yoonpay.client.WebhookSignature;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Guards the paths Yoon posts events to ({@code yoon.webhook.paths}); every other request passes
 * untouched. A wrong or stale signature over the raw body gets 401 without reaching the
 * controller; an already-handled event gets 200 without reaching it; otherwise the verified
 * {@link YoonEvent} is put in the request attribute {@value #EVENT_ATTRIBUTE} (or injected as a
 * controller argument) and its id is remembered only after the controller answered 2xx — a
 * failed handling is delivered again.
 */
public class YoonWebhookFilter extends OncePerRequestFilter {

    public static final String EVENT_ATTRIBUTE = "dev.yoonpay.spring.YoonEvent";

    private final String secret;
    private final List<String> paths;
    private final YoonEventStore store;

    public YoonWebhookFilter(String secret, List<String> paths, YoonEventStore store) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Set yoon.webhook-secret (YOON_WEBHOOK_SECRET) to receive Yoon's webhooks");
        }
        this.secret = secret;
        this.paths = List.copyOf(paths);
        this.store = store;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !"POST".equals(request.getMethod()) || !paths.contains(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        byte[] raw = request.getInputStream().readAllBytes();
        // The Java client verifies a String: decode exactly as the bytes were signed (UTF-8).
        if (!WebhookSignature.verify(secret, request.getHeader(WebhookSignature.HEADER), new String(raw, StandardCharsets.UTF_8))) {
            reply(response, 401, "{\"error\":\"invalid signature\"}");
            return;
        }
        YoonEvent event;
        try {
            event = YoonEvent.fromJson(raw);
        } catch (IllegalArgumentException e) {
            reply(response, 400, "{\"error\":\"not a Yoon event\"}");
            return;
        }
        if (store.has(event.id())) {
            reply(response, 200, "{\"duplicate\":true}");
            return;
        }
        request.setAttribute(EVENT_ATTRIBUTE, event);
        chain.doFilter(new RawBodyRequest(request, raw), response);
        int status = response.getStatus();
        if (status >= 200 && status < 300) {
            store.add(event.id());
        }
    }

    private static void reply(HttpServletResponse response, int status, String json) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
    }

    /** Hands the controller the body this filter already read. */
    private static final class RawBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        RawBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
