package dev.yoonpay.server.portal;

import dev.yoonpay.core.id.Ids;
import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.billing.CatalogRepository;
import dev.yoonpay.server.billing.CustomerRepository;
import dev.yoonpay.server.billing.PriceRecord;
import dev.yoonpay.server.config.YoonProperties;
import dev.yoonpay.server.web.ApiProblem;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * Customer portal sessions (ADR-0031): one customer, a short lifetime, and the actions the
 * merchant allows. The URL token is random; only its SHA-256 is stored.
 */
@Service
public class PortalService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PortalSessionRepository sessions;
    private final CustomerRepository customers;
    private final CatalogRepository catalog;
    private final YoonProperties properties;
    private final Clock clock;

    public PortalService(PortalSessionRepository sessions, CustomerRepository customers, CatalogRepository catalog,
                         YoonProperties properties, Clock clock) {
        this.sessions = sessions;
        this.customers = customers;
        this.catalog = catalog;
        this.properties = properties;
        this.clock = clock;
    }

    public PortalSessionResponse create(AppPrincipal app, String customerId, CreatePortalSessionRequest req) {
        if (properties.publicUrl() == null) {
            throw ApiProblem.unprocessable("public_url_required", "The customer portal needs YOON_PUBLIC_URL: its URL must be absolute");
        }
        customers.find(app.id(), customerId).orElseThrow(() -> ApiProblem.notFound("Customer", customerId));
        if (req.returnUrl() != null) {
            checkUrl(req.returnUrl());
        }
        CreatePortalSessionRequest.Allow allow = req.allow();
        String prices = null;
        if (allow != null && allow.changePrices() != null && !allow.changePrices().isEmpty()) {
            for (String id : new LinkedHashSet<>(allow.changePrices())) {
                PriceRecord p = catalog.findPrice(app.id(), id)
                        .orElseThrow(() -> ApiProblem.unprocessable("price_not_found", "No price " + id));
                if (!p.active()) {
                    throw ApiProblem.unprocessable("price_archived", "Price " + id + " is archived");
                }
            }
            prices = String.join(",", new LinkedHashSet<>(allow.changePrices()));
        }
        String token = newToken();
        Instant expiresAt = clock.instant().plus(properties.app(app.name()).billing().portalTtl());
        PortalSessionRecord s = new PortalSessionRecord(Ids.next("ps"), app.id(), customerId, hash(token),
                allow != null && Boolean.TRUE.equals(allow.cancel()), prices,
                allow != null && Boolean.TRUE.equals(allow.coupons()), req.returnUrl(), expiresAt, null);
        sessions.insert(s);
        String url = properties.publicUrl().resolve("/checkout/portal/" + s.id() + "?t=" + token).toString();
        return new PortalSessionResponse(s.id(), "portal_session", customerId, url, expiresAt);
    }

    /** The session for a public request, if the token matches and it has not expired; else empty (a 404). */
    public Optional<PortalSessionRecord> authorize(String id, String token) {
        if (id == null || token == null || token.isEmpty() || token.length() > 128 || id.length() > 64) {
            return Optional.empty();
        }
        return sessions.find(id)
                .filter(s -> MessageDigest.isEqual(s.tokenHash().getBytes(StandardCharsets.US_ASCII),
                        hash(token).getBytes(StandardCharsets.US_ASCII)))
                .filter(s -> clock.instant().isBefore(s.expiresAt()));
    }

    /** The prices this session lets the customer change to. */
    public static List<String> changePrices(PortalSessionRecord s) {
        return s.changePrices() == null ? List.of() : List.of(s.changePrices().split(","));
    }

    private static void checkUrl(String value) {
        try {
            URI u = URI.create(value);
            if (!"https".equals(u.getScheme()) && !"http".equals(u.getScheme())) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw ApiProblem.invalid("return_url must be an http(s) URL");
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
