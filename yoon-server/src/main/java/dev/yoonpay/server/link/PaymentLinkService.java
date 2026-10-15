package dev.yoonpay.server.link;

import dev.yoonpay.core.id.Ids;
import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.billing.Metadata;
import dev.yoonpay.server.config.YoonProperties;
import dev.yoonpay.server.web.ApiProblem;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Currency;
import java.util.List;
import java.util.Set;

/** Payment links (ADR-0030): amounts fixed at creation; archived, never deleted. */
@Service
public class PaymentLinkService {

    private static final Set<String> PATCHABLE = Set.of("active", "description", "expires_at", "max_payments", "metadata");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PaymentLinkRepository links;
    private final YoonProperties properties;
    private final ObjectMapper json;
    private final Clock clock;

    public PaymentLinkService(PaymentLinkRepository links, YoonProperties properties, ObjectMapper json, Clock clock) {
        this.links = links;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    public PaymentLinkResponse create(AppPrincipal app, CreatePaymentLinkRequest req) {
        if (properties.publicUrl() == null) {
            throw ApiProblem.unprocessable("public_url_required", "Payment links need YOON_PUBLIC_URL: the link must be absolute");
        }
        if (req.amount() != null && (req.minAmount() != null || req.maxAmount() != null)) {
            throw ApiProblem.invalid("give either a fixed amount, or min_amount / max_amount for the customer to choose");
        }
        if (req.minAmount() != null && req.maxAmount() != null && req.minAmount() > req.maxAmount()) {
            throw ApiProblem.invalid("min_amount must not exceed max_amount");
        }
        try {
            Currency.getInstance(req.currency());
        } catch (IllegalArgumentException e) {
            throw ApiProblem.invalid("unknown currency " + req.currency());
        }
        if (req.expiresAt() != null && !req.expiresAt().isAfter(clock.instant())) {
            throw ApiProblem.invalid("expires_at must be in the future");
        }
        if (req.returnUrl() != null) {
            checkUrl(req.returnUrl());
        }
        PaymentLinkRecord l = new PaymentLinkRecord(Ids.next("plink"), app.id(), newCode(), req.amount(), req.minAmount(),
                req.maxAmount(), req.currency(), req.country(), req.description().strip(), req.method(),
                req.maxPayments(), req.expiresAt(), req.returnUrl(), true, Metadata.metadata(req.metadata(), json),
                null, null);
        links.insert(l);
        return get(app, l.id());
    }

    public PaymentLinkResponse get(AppPrincipal app, String id) {
        return view(find(app, id));
    }

    public List<PaymentLinkResponse> views(List<PaymentLinkRecord> rows) {
        return rows.stream().map(this::view).toList();
    }

    public PaymentLinkResponse update(AppPrincipal app, String id, JsonNode body) {
        PaymentLinkRecord l = find(app, id);
        if (body == null || !body.isObject()) {
            throw ApiProblem.invalid("the body must be a JSON object");
        }
        for (String field : body.propertyNames()) {
            if (!PATCHABLE.contains(field)) {
                throw ApiProblem.invalid("unknown or read-only field: " + field + " (the amount never changes: create a new link)");
            }
        }
        boolean active = l.active();
        if (body.has("active")) {
            if (!body.get("active").isBoolean()) {
                throw ApiProblem.invalid("active must be true or false");
            }
            active = body.get("active").asBoolean();
        }
        String description = l.description();
        if (body.has("description")) {
            JsonNode d = body.get("description");
            if (!d.isString() || d.asString().isBlank() || d.asString().length() > 500) {
                throw ApiProblem.invalid("description must be 1 to 500 characters");
            }
            description = d.asString().strip();
        }
        Instant expiresAt = l.expiresAt();
        if (body.has("expires_at")) {
            JsonNode e = body.get("expires_at");
            try {
                expiresAt = e.isNull() ? null : Instant.parse(e.asString());
            } catch (RuntimeException ex) {
                throw ApiProblem.invalid("expires_at must be an ISO-8601 instant or null");
            }
        }
        Integer maxPayments = l.maxPayments();
        if (body.has("max_payments")) {
            JsonNode m = body.get("max_payments");
            if (!m.isNull() && (!m.isInt() || m.asInt() < 1)) {
                throw ApiProblem.invalid("max_payments must be a positive integer or null");
            }
            maxPayments = m.isNull() ? null : m.asInt();
        }
        String metadata = body.has("metadata") ? Metadata.metadata(body.get("metadata"), json) : l.metadata();
        links.update(new PaymentLinkRecord(l.id(), l.applicationId(), l.code(), l.amount(), l.minAmount(), l.maxAmount(),
                l.currency(), l.country(), description, l.method(), maxPayments, expiresAt, l.returnUrl(), active,
                metadata, l.createdAt(), l.updatedAt()));
        return get(app, id);
    }

    private PaymentLinkResponse view(PaymentLinkRecord l) {
        PaymentLinkRepository.Stats stats = links.stats(l.id());
        String url = publicUrl("/l/" + l.code());
        return new PaymentLinkResponse(l.id(), "payment_link", url, url + "/qr.svg", l.amount(), l.minAmount(),
                l.maxAmount(), l.currency(), l.country(), l.description(), l.method(), l.maxPayments(), l.expiresAt(),
                l.returnUrl(), l.active(), stats.paymentsCount(), stats.amountCollected(),
                Metadata.read(l.metadata(), json), l.createdAt());
    }

    private String publicUrl(String path) {
        return properties.publicUrl() == null ? path : properties.publicUrl().resolve(path).toString();
    }

    private PaymentLinkRecord find(AppPrincipal app, String id) {
        return links.find(app.id(), id).orElseThrow(() -> ApiProblem.notFound("Payment link", id));
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

    /** 16 random bytes → 22 URL-safe characters: not guessable, short enough to share. */
    private static String newCode() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
