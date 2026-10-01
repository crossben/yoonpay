package dev.yoonpay.provider.wave;

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

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Currency;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Wave, directly through the Wave Business API (docs.wave.com): checkout sessions, payouts and
 * full refunds. See ADR-0020.
 *
 * <p>Credentials: {@code api-key} (Wave Business portal; one key per country account, with the
 * Checkout and Payout permissions you need), {@code webhook-secret} (the webhook's signing
 * secret, {@code wave_..._WHS_...}), {@code countries} (comma-separated, default {@code SN}),
 * {@code base-url} (default {@code https://api.wave.com}).
 *
 * <p>Checkout sessions and payouts carry Yoon's reference as {@code client_reference}, so a lost
 * answer is found by search; payouts send it as {@code Idempotency-Key} too.
 */
public final class WaveProvider implements PaymentProvider {

    static final ProviderId ID = new ProviderId("wave");
    static final String METHOD = "wave";
    private static final Currency XOF = Currency.getInstance("XOF");
    /** Wave's XOF countries. Gambia (GMD) and Uganda (UGX) are left out until tested. */
    private static final Set<String> XOF_COUNTRIES = Set.of("SN", "CI", "ML", "BF");

    private final ProviderHttp http;
    private final String apiKey;
    private final String webhookSecret;
    private final URI base;
    private final Set<String> countries;

    WaveProvider(Credentials credentials, ProviderHttp http) {
        this.http = http;
        this.apiKey = credentials.require("api-key");
        this.webhookSecret = credentials.require("webhook-secret");
        this.base = URI.create(credentials.optional("base-url", "https://api.wave.com"));
        Set<String> cs = new HashSet<>();
        for (String c : credentials.optional("countries", "SN").split(",")) {
            String code = c.trim().toUpperCase(Locale.ROOT);
            if (!XOF_COUNTRIES.contains(code)) {
                throw new IllegalArgumentException("wave: unsupported country '" + code + "' (supported: " + XOF_COUNTRIES + ")");
            }
            cs.add(code);
        }
        this.countries = Set.copyOf(cs);
    }

    @Override
    public ProviderId id() {
        return ID;
    }

    @Override
    public Capabilities capabilities() {
        Set<Capability> caps = new HashSet<>();
        for (String c : countries) {
            for (Operation op : Operation.values()) {
                caps.add(new Capability(op, c, METHOD, XOF));
            }
        }
        return new Capabilities(caps);
    }

    private Map<String, String> headers() {
        return Map.of("Authorization", "Bearer " + apiKey);
    }

    private URI path(String p) {
        return base.resolve(base.getPath() + p);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private JsonNode read(String p) {
        if (!(http.get(path(p), headers()) instanceof Result.Answered a) || !ProviderHttp.isSuccess(a.status())) {
            return Json.parse(null);
        }
        return Json.parse(a.body());
    }

    // ------------------------------------------------------------------ collect

    @Override
    public CallOutcome collect(CollectRequest request) {
        if (request.returnUrl() == null) {
            return new CallOutcome.Rejected("RETURN_URL_REQUIRED", "Wave checkout needs return_url (success and error page)");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", Long.toString(request.amount().amount()));
        body.put("currency", request.amount().currency().getCurrencyCode());
        body.put("success_url", request.returnUrl().toString());
        body.put("error_url", request.returnUrl().toString());
        body.put("client_reference", request.attemptReference());
        if (request.customerPhone() != null) {
            body.put("restrict_payer_mobile", request.customerPhone());
        }
        // No money moves when a session is created: a lost answer is found by client_reference.
        return switch (http.postJson(path("/v1/checkout/sessions"), headers(), Json.write(body))) {
            case Result.NotSent n -> new CallOutcome.Rejected("PROVIDER_UNAVAILABLE", n.cause());
            case Result.Lost l -> new CallOutcome.Unknown(l.cause());
            case Result.Answered a when ProviderHttp.isServerError(a.status()) -> new CallOutcome.Unknown("HTTP " + a.status());
            case Result.Answered a when ProviderHttp.isSuccess(a.status()) -> {
                JsonNode json = Json.parse(a.body());
                Optional<String> id = Json.text(json, "id");
                Optional<String> url = Json.text(json, "wave_launch_url");
                yield id.isPresent() && url.isPresent()
                        ? new CallOutcome.Accepted(new ProviderReference(id.get()), URI.create(url.get()), null)
                        : new CallOutcome.Unknown("no id or wave_launch_url in success response");
            }
            case Result.Answered a -> new CallOutcome.Rejected(code(a), message(a.body()));
        };
    }

    @Override
    public StatusResult<PaymentStatus> status(ProviderReference payment) {
        JsonNode json = read("/v1/checkout/sessions/" + encode(payment.value()));
        String paymentStatus = Json.text(json, "payment_status").orElse(null);
        String checkoutStatus = Json.text(json, "checkout_status").orElse(null);
        String raw = paymentStatus == null ? null : paymentStatus + "/" + checkoutStatus;
        return new StatusResult<>(WaveStatus.checkout(paymentStatus, checkoutStatus), raw, amount(json));
    }

    // ------------------------------------------------------------------ payouts

    @Override
    public CallOutcome payout(PayoutRequest request) {
        if (request.recipientPhone() == null) {
            return new CallOutcome.Rejected("PHONE_REQUIRED", "Wave pays out to a phone number (recipient.phone)");
        }
        if (!countries.contains(request.country())) {
            return new CallOutcome.Rejected("METHOD_NOT_SUPPORTED", "Wave is not configured for " + request.country());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("currency", request.amount().currency().getCurrencyCode());
        body.put("receive_amount", Long.toString(request.amount().amount()));
        body.put("mobile", request.recipientPhone());
        body.put("client_reference", request.attemptReference());
        Map<String, String> headers = new LinkedHashMap<>(headers());
        headers.put("Idempotency-Key", request.attemptReference());

        return switch (http.postJson(path("/v1/payout"), headers, Json.write(body))) {
            case Result.NotSent n -> new CallOutcome.Rejected("PROVIDER_UNAVAILABLE", n.cause());
            case Result.Lost l -> new CallOutcome.Unknown(l.cause());
            case Result.Answered a when ProviderHttp.isSuccess(a.status()) -> Json.text(Json.parse(a.body()), "id")
                    .<CallOutcome>map(id -> new CallOutcome.Accepted(new ProviderReference(id), null, null))
                    // Wave: in outages a payout may exist without an id being returned.
                    .orElseGet(() -> new CallOutcome.Unknown("no id in success response"));
            // Refused before any money moved (validation, auth, balance, limits, rate limit).
            case Result.Answered a when a.status() == 400 || a.status() == 401 || a.status() == 403
                    || a.status() == 422 || a.status() == 429 ->
                    new CallOutcome.Rejected(code(a), message(a.body()));
            // 5xx, 409 (e.g. idempotency-mismatch), anything else: ask, don't guess.
            case Result.Answered a -> new CallOutcome.Unknown("HTTP " + a.status());
        };
    }

    @Override
    public StatusResult<PayoutStatus> payoutStatus(ProviderReference payout) {
        JsonNode json = read("/v1/payout/" + encode(payout.value()));
        String raw = Json.text(json, "status").orElse(null);
        return new StatusResult<>(WaveStatus.payout(raw), raw,
                Json.wholeAmount(json, "receive_amount").map(v -> new Money(v, XOF)).orElse(null));
    }

    // ------------------------------------------------------------------ refunds

    /** Full refunds of a checkout session only. Refunding twice creates no second transaction. */
    @Override
    public CallOutcome refund(RefundRequest request) {
        String session = request.payment().value();
        JsonNode json = read("/v1/checkout/sessions/" + encode(session));
        Money paid = amount(json);
        if (paid == null) {
            return new CallOutcome.Rejected("PAYMENT_NOT_FOUND", "Wave does not know this checkout session");
        }
        if (!paid.equals(request.amount())) {
            return new CallOutcome.Rejected("PARTIAL_REFUND_NOT_SUPPORTED",
                    "Wave refunds the full amount only; refund the rest with a payout");
        }
        ProviderReference ref = new ProviderReference(session);
        return switch (postRefund(session)) {
            case Result.NotSent n -> new CallOutcome.Rejected("PROVIDER_UNAVAILABLE", n.cause());
            case Result.Lost l -> new CallOutcome.Unknown(l.cause(), ref);
            case Result.Answered a when ProviderHttp.isSuccess(a.status()) -> new CallOutcome.Accepted(ref, null, null);
            case Result.Answered a when a.status() == 400 || a.status() == 401 || a.status() == 403
                    || a.status() == 404 || a.status() == 409 || a.status() == 422 ->
                    new CallOutcome.Rejected(code(a), message(a.body()));
            case Result.Answered a -> new CallOutcome.Unknown("HTTP " + a.status(), ref);
        };
    }

    /**
     * Wave has no refund status. Its refund call is idempotent and answers 200 once the payment
     * is refunded, so asking again <em>is</em> the status check: 200 means refunded.
     */
    @Override
    public StatusResult<RefundStatus> refundStatus(ProviderReference refund) {
        if (postRefund(refund.value()) instanceof Result.Answered a && ProviderHttp.isSuccess(a.status())) {
            return new StatusResult<>(RefundStatus.REFUNDED, "refunded", null);
        }
        return new StatusResult<>(null, null, null);
    }

    private Result postRefund(String session) {
        return http.postJson(path("/v1/checkout/sessions/" + encode(session) + "/refund"), headers(), "{}");
    }

    /** Sessions and payouts carry our reference as client_reference. */
    @Override
    public Optional<ProviderReference> lookup(Operation operation, String attemptReference) {
        String p = switch (operation) {
            case COLLECT -> "/v1/checkout/sessions/search?client_reference=";
            case PAYOUT -> "/v1/payouts/search?client_reference=";
            case REFUND -> null;
        };
        if (p == null) {
            return Optional.empty();
        }
        JsonNode result = read(p + encode(attemptReference)).path("result");
        return result.isArray() && result.size() == 1
                ? Json.text(result.path(0), "id").map(ProviderReference::new) : Optional.empty();
    }

    // ------------------------------------------------------------------ callbacks

    /**
     * {@code Wave-Signature: t=<unix time>,v1=<hex>[,v1=<hex>…]}: HMAC-SHA256 of
     * {@code t + raw body} with the webhook secret; any {@code v1} may match (key rotation).
     * Webhooks set up with Wave's "shared secret" method send the secret as a Bearer token instead.
     * No timestamp window: Yoon verifies stored callbacks later, and a replayed callback changes
     * nothing because the status API decides.
     */
    @Override
    public WebhookVerification verify(InboundWebhook hook) {
        byte[] body = hook.rawBody();
        boolean valid = signatureValid(hook.header("wave-signature"), body)
                || bearerValid(hook.header("authorization"));
        JsonNode data = Json.parse(new String(body, StandardCharsets.UTF_8)).path("data");
        String ref = Json.text(data, "id").orElse(null);
        return new WebhookVerification(valid, ref == null ? null : new ProviderReference(ref));
    }

    private boolean signatureValid(String header, byte[] body) {
        if (header == null) {
            return false;
        }
        String t = null;
        List<String> signatures = new java.util.ArrayList<>();
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals("t")) {
                t = kv[1];
            } else if (kv.length == 2 && kv[0].equals("v1")) {
                signatures.add(kv[1]);
            }
        }
        if (t == null) {
            return false;
        }
        byte[] tb = t.getBytes(StandardCharsets.UTF_8);
        byte[] signed = Arrays.copyOf(tb, tb.length + body.length);
        System.arraycopy(body, 0, signed, tb.length, body.length);
        String expected = Signatures.hmacSha256Hex(webhookSecret, signed);
        return signatures.stream().anyMatch(s -> Signatures.hexEquals(expected, s));
    }

    private boolean bearerValid(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        return MessageDigest.isEqual(webhookSecret.getBytes(StandardCharsets.UTF_8),
                header.substring(7).trim().getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ helpers

    private static Money amount(JsonNode json) {
        Optional<String> currency = Json.text(json, "currency");
        return Json.wholeAmount(json, "amount")
                .map(v -> new Money(v, Currency.getInstance(currency.orElse("XOF"))))
                .orElse(null);
    }

    private static String code(Result.Answered a) {
        return Json.text(Json.parse(a.body()), "error.code")
                .map(c -> "WAVE_" + c.toUpperCase(Locale.ROOT).replace('-', '_'))
                .orElse("WAVE_HTTP_" + a.status());
    }

    private static String message(String body) {
        return Json.text(Json.parse(body), "error.message").orElse("Refused by Wave");
    }
}
