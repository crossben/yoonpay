package dev.yoonpay.provider.pispi;

import dev.yoonpay.core.lifecycle.PaymentStatus;
import dev.yoonpay.core.lifecycle.PayoutStatus;
import dev.yoonpay.core.lifecycle.RefundStatus;
import dev.yoonpay.core.money.Money;
import dev.yoonpay.core.provider.CallOutcome;
import dev.yoonpay.core.provider.Capabilities;
import dev.yoonpay.core.provider.Capability;
import dev.yoonpay.core.provider.CollectRequest;
import dev.yoonpay.core.provider.InboundWebhook;
import dev.yoonpay.core.provider.Operation;
import dev.yoonpay.core.provider.PaymentProvider;
import dev.yoonpay.core.provider.PayoutRequest;
import dev.yoonpay.core.provider.ProviderId;
import dev.yoonpay.core.provider.ProviderReference;
import dev.yoonpay.core.provider.RefundRequest;
import dev.yoonpay.core.provider.StatusResult;
import dev.yoonpay.core.provider.WebhookVerification;
import dev.yoonpay.provider.support.Credentials;
import dev.yoonpay.provider.support.Json;
import dev.yoonpay.provider.support.ProviderHttp;
import dev.yoonpay.provider.support.ProviderHttp.Result;
import dev.yoonpay.provider.support.Signatures;
import tools.jackson.databind.JsonNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Currency;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * PI-SPI, the BCEAO's instant-payment platform, through the merchant's participant (bank or
 * e-money issuer) and its API Business (specification 1.5.0). See ADR-0019.
 *
 * <p>Credentials: {@code base-url} (the participant's API Business URL), {@code token-url},
 * {@code client-id}, {@code client-secret}, {@code api-key}, {@code merchant-alias} (the
 * merchant's PI alias), {@code webhook-secret} (returned by {@code POST /webhooks}),
 * {@code client-cert} / {@code client-key} / {@code ca-cert} (PEM text or file path; mutual TLS
 * with the certificate the BCEAO issued), {@code scope} (optional).
 *
 * <p>Every operation is keyed on Yoon's attempt reference as {@code txId}, so a lost answer can
 * always be looked up. Refunds are PI-SPI "returns of funds": full amount, keyed on the received
 * payment's {@code end2endId}.
 */
public final class PiSpiProvider implements PaymentProvider {

    static final ProviderId ID = new ProviderId("pispi");
    static final String METHOD = "pispi";
    private static final Currency XOF = Currency.getInstance("XOF");
    /** The eight UEMOA states. */
    private static final List<String> COUNTRIES = List.of("BJ", "BF", "CI", "GW", "ML", "NE", "SN", "TG");
    /** Category 521: e-commerce payment, immediate. */
    private static final String CATEGORY_ECOMMERCE = "521";
    private static final String SCOPES = "demande_paiement.write demande_paiement.read paiement.write paiement.read retour_fonds.write";

    private final ProviderHttp http;
    private final URI base;
    private final URI tokenUrl;
    private final String clientId;
    private final String clientSecret;
    private final String apiKey;
    private final String merchantAlias;
    private final String webhookSecret;
    private final String scope;
    private final Clock clock;

    private String token;
    private Instant tokenExpiry = Instant.EPOCH;

    PiSpiProvider(Credentials credentials, ProviderHttp http) {
        this(credentials, http, Clock.systemUTC());
    }

    PiSpiProvider(Credentials credentials, ProviderHttp http, Clock clock) {
        this.http = http;
        this.clock = clock;
        this.base = URI.create(credentials.require("base-url"));
        this.tokenUrl = URI.create(credentials.require("token-url"));
        this.clientId = credentials.require("client-id");
        this.clientSecret = credentials.require("client-secret");
        this.apiKey = credentials.require("api-key");
        this.merchantAlias = credentials.require("merchant-alias");
        this.webhookSecret = credentials.require("webhook-secret");
        this.scope = credentials.optional("scope", SCOPES);
    }

    @Override
    public ProviderId id() {
        return ID;
    }

    @Override
    public Capabilities capabilities() {
        Set<Capability> caps = new HashSet<>();
        for (String c : COUNTRIES) {
            for (Operation op : Operation.values()) {
                caps.add(new Capability(op, c, METHOD, XOF));
            }
        }
        return new Capabilities(caps);
    }

    // ------------------------------------------------------------------ auth

    /** An OAuth2 client-credentials token, cached until shortly before it expires; null if none could be had. */
    private synchronized String token() {
        if (token != null && clock.instant().isBefore(tokenExpiry)) {
            return token;
        }
        String basic = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        form.put("scope", scope);
        if (!(http.postForm(tokenUrl, Map.of("Authorization", "Basic " + basic), form) instanceof Result.Answered a)
                || !ProviderHttp.isSuccess(a.status())) {
            return null;
        }
        JsonNode json = Json.parse(a.body());
        Optional<String> t = Json.text(json, "access_token");
        if (t.isEmpty()) {
            return null;
        }
        long ttl = Json.wholeAmount(json, "expires_in").orElse(300L);
        token = t.get();
        tokenExpiry = clock.instant().plusSeconds(Math.max(0, ttl - 30));
        return token;
    }

    private Map<String, String> headers(String token) {
        return Map.of("Authorization", "Bearer " + token, "x-api-key", apiKey);
    }

    private URI path(String p) {
        return base.resolve(base.getPath() + p);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** A GET that returns the JSON of a 2xx answer, or a missing node. */
    private JsonNode read(String p) {
        String t = token();
        if (t == null || !(http.get(path(p), headers(t)) instanceof Result.Answered a) || !ProviderHttp.isSuccess(a.status())) {
            return Json.parse(null);
        }
        return Json.parse(a.body());
    }

    // ------------------------------------------------------------------ collect

    @Override
    public CallOutcome collect(CollectRequest request) {
        if (request.customerAlias() == null) {
            return new CallOutcome.Rejected("PI_ALIAS_REQUIRED", "PI-SPI needs the customer's PI alias (customer.pi_alias)");
        }
        String t = token();
        if (t == null) {
            return new CallOutcome.Rejected("PROVIDER_UNAVAILABLE", "no PI-SPI access token");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("txId", request.attemptReference());
        body.put("payeurAlias", request.customerAlias());
        body.put("payeAlias", merchantAlias);
        body.put("categorie", CATEGORY_ECOMMERCE);
        body.put("confirmation", false);
        body.put("montant", request.amount().amount());
        body.put("motif", request.description() == null ? "Payment" : request.description());
        ProviderReference ref = new ProviderReference(request.attemptReference());

        return mutating(http.postJson(path("/demandes-paiements"), headers(t), Json.write(body)), ref,
                new CallOutcome.Accepted(ref, null, "Approve the payment request in your banking or wallet app"));
    }

    @Override
    public StatusResult<PaymentStatus> status(ProviderReference payment) {
        JsonNode json = read("/demandes-paiements/" + encode(payment.value()));
        String raw = Json.text(json, "statut").orElse(null);
        return new StatusResult<>(PiSpiStatus.request(raw), raw, amount(json));
    }

    // ------------------------------------------------------------------ payouts

    @Override
    public CallOutcome payout(PayoutRequest request) {
        if (request.recipientAlias() == null) {
            return new CallOutcome.Rejected("PI_ALIAS_REQUIRED", "PI-SPI needs the recipient's PI alias (recipient.pi_alias)");
        }
        String t = token();
        if (t == null) {
            return new CallOutcome.Rejected("PROVIDER_UNAVAILABLE", "no PI-SPI access token");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("txId", request.attemptReference());
        body.put("payeurAlias", merchantAlias);
        body.put("payeAlias", request.recipientAlias());
        body.put("confirmation", false);
        body.put("montant", request.amount().amount());
        body.put("motif", "Payout");
        ProviderReference ref = new ProviderReference(request.attemptReference());

        return mutating(http.postJson(path("/paiements-envoyes"), headers(t), Json.write(body)), ref,
                new CallOutcome.Accepted(ref, null, null));
    }

    @Override
    public StatusResult<PayoutStatus> payoutStatus(ProviderReference payout) {
        JsonNode json = read("/paiements-envoyes/" + encode(payout.value()));
        String raw = Json.text(json, "statut").orElse(null);
        return new StatusResult<>(PiSpiStatus.payout(raw), raw, amount(json));
    }

    // ------------------------------------------------------------------ refunds (returns of funds)

    @Override
    public CallOutcome refund(RefundRequest request) {
        // Find the payment the customer made in answer to our request: it carries the request's txId.
        JsonNode received = firstOf(read("/paiements-recus?txId=" + encode(request.payment().value())));
        Optional<String> e2e = Json.text(received, "end2endId");
        if (e2e.isEmpty()) {
            return new CallOutcome.Rejected("PAYMENT_NOT_FOUND", "PI-SPI has no received payment for this request");
        }
        Money paid = amount(received);
        if (paid == null || !paid.equals(request.amount())) {
            return new CallOutcome.Rejected("PARTIAL_REFUND_NOT_SUPPORTED",
                    "PI-SPI returns the full amount only; refund the rest with a payout");
        }
        String t = token();
        if (t == null) {
            return new CallOutcome.Rejected("PROVIDER_UNAVAILABLE", "no PI-SPI access token");
        }
        ProviderReference ref = new ProviderReference(e2e.get());
        // Idempotent per end2endId: a return already requested is answered, not duplicated.
        return mutating(http.putJson(path("/paiements/" + encode(e2e.get()) + "/retours"), headers(t), null), ref,
                new CallOutcome.Accepted(ref, null, null));
    }

    @Override
    public StatusResult<RefundStatus> refundStatus(ProviderReference refund) {
        JsonNode json = read("/paiements/" + encode(refund.value()));
        String raw = Json.text(json, "retourStatut").orElse(null);
        return new StatusResult<>(PiSpiStatus.refund(raw), raw, raw == null ? null : amount(json));
    }

    /** Requests and payouts are keyed on Yoon's reference; a refund is found from its payment, not its own reference. */
    @Override
    public Optional<ProviderReference> lookup(Operation operation, String attemptReference) {
        String p = switch (operation) {
            case COLLECT -> "/demandes-paiements/";
            case PAYOUT -> "/paiements-envoyes/";
            case REFUND -> null;
        };
        if (p == null) {
            return Optional.empty();
        }
        return Json.text(read(p + encode(attemptReference)), "txId").isPresent()
                ? Optional.of(new ProviderReference(attemptReference)) : Optional.empty();
    }

    // ------------------------------------------------------------------ callbacks

    /**
     * {@code X-Signature}: HMAC-SHA256 of the raw body with the webhook secret. The specification
     * does not fix the encoding, so hex and base64 are both accepted.
     */
    @Override
    public WebhookVerification verify(InboundWebhook hook) {
        byte[] body = hook.rawBody();
        String sent = hook.header("x-signature");
        boolean valid = sent != null && (Signatures.hexEquals(Signatures.hmacSha256Hex(webhookSecret, body), sent)
                || base64Equals(hmac(body), sent.trim()));
        JsonNode root = Json.parse(new String(body, StandardCharsets.UTF_8));
        JsonNode event = firstOf(root);
        String ref = Json.text(event, "txId").orElse(null);
        return new WebhookVerification(valid, ref == null ? null : new ProviderReference(ref));
    }

    private byte[] hmac(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean base64Equals(byte[] expected, String sent) {
        try {
            return MessageDigest.isEqual(expected, Base64.getDecoder().decode(sent));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A mutating call's answer. 2xx: accepted. 400/401/403/404/422 refuse before money moves
     * (e.g. {@code BE23} unknown alias). A {@code DU03} (duplicate txId) means our earlier
     * request got there: ask the status API. 5xx, lost answers, anything else: unknown.
     */
    private static CallOutcome mutating(Result result, ProviderReference ref, CallOutcome.Accepted accepted) {
        return switch (result) {
            case Result.NotSent n -> new CallOutcome.Rejected("PROVIDER_UNAVAILABLE", n.cause());
            case Result.Lost l -> new CallOutcome.Unknown(l.cause(), ref);
            case Result.Answered a when ProviderHttp.isSuccess(a.status()) -> accepted;
            case Result.Answered a when "DU03".equals(reason(a.body())) -> new CallOutcome.Unknown("duplicate txId (DU03)", ref);
            case Result.Answered a when a.status() == 400 || a.status() == 401 || a.status() == 403
                    || a.status() == 404 || a.status() == 422 ->
                    new CallOutcome.Rejected(reason(a.body()) == null ? "PISPI_HTTP_" + a.status() : "PISPI_" + reason(a.body()),
                            message(a.body()));
            case Result.Answered a -> new CallOutcome.Unknown("HTTP " + a.status(), ref);
        };
    }

    private static String reason(String body) {
        JsonNode json = Json.parse(body);
        return Json.text(json, "statutRaison").or(() -> Json.text(json, "code")).orElse(null);
    }

    private static String message(String body) {
        JsonNode json = Json.parse(body);
        return Json.text(json, "detail").or(() -> Json.text(json, "title")).or(() -> Json.text(json, "message"))
                .orElse("Refused by PI-SPI");
    }

    /** The first element of {@code {data: [...]}}, or the node itself. */
    private static JsonNode firstOf(JsonNode root) {
        JsonNode data = root.path("data");
        return data.isArray() ? data.path(0) : root;
    }

    /** {@code montant} is in XOF, which has no minor unit. */
    private static Money amount(JsonNode json) {
        return Json.wholeAmount(json, "montant").map(v -> new Money(v, XOF)).orElse(null);
    }
}
