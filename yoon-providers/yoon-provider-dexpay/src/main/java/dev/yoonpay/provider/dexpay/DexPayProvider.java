package dev.yoonpay.provider.dexpay;

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
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * DEXCHANGE Pay (DexPay): hosted checkout sessions and payouts. The customer picks the wallet on
 * DexPay's page, so the method is informational for collections. No refund API.
 *
 * <p>Credentials: {@code api-key}, {@code api-secret}, {@code webhook-secret} (from the DexPay
 * dashboard; falls back to the API secret), {@code mode} ({@code sandbox} or {@code live};
 * default sandbox). Payout operator codes can be pinned with {@code payout-operator-<method>}.
 *
 * <p>Both the checkout status and payouts are keyed on <em>Yoon's own reference</em>, so a lost
 * answer can always be looked up, and payouts send it as {@code Idempotency-Key}.
 *
 * <p>Lessons carried over: callbacks sign the raw body with HMAC-SHA256 in
 * {@code x-webhook-signature} (older docs: {@code x-dexchange-signature}; sometimes prefixed
 * {@code sha256=}); the signing secret is a separate dashboard value, not the API key; amounts
 * are integers; a 5xx on a payout may have moved money.
 */
public final class DexPayProvider implements PaymentProvider {

    static final ProviderId ID = new ProviderId("dexpay");
    private static final Currency XOF = Currency.getInstance("XOF");
    private static final Set<String> COLLECT_METHODS = Set.of("wave", "orange_money", "free_money", "card");

    /** Default payout operator codes (Senegal). DexPay can rename them: pin with credentials if needed. */
    static final Map<String, String> DEFAULT_OPERATORS = Map.of(
            "wave", "wave_sn_payout",
            "orange_money", "om_sn_payout");

    private final ProviderHttp http;
    private final String apiKey;
    private final String apiSecret;
    private final String webhookSecret;
    private final URI base;
    private final Map<String, String> operators;

    DexPayProvider(Credentials credentials, ProviderHttp http) {
        this.http = http;
        this.apiKey = credentials.require("api-key");
        this.apiSecret = credentials.require("api-secret");
        this.webhookSecret = credentials.optional("webhook-secret", apiSecret);
        boolean live = credentials.optional("mode", "sandbox").equalsIgnoreCase("live");
        this.base = URI.create(credentials.optional("base-url",
                live ? "https://api.dexpay.africa/api/v1" : "https://api-sandbox.dexpay.africa/api/v1"));
        Map<String, String> ops = new HashMap<>(DEFAULT_OPERATORS);
        for (String method : Set.of("wave", "orange_money", "free_money")) {
            String pinned = credentials.optional("payout-operator-" + method, null);
            if (pinned != null) {
                ops.put(method, pinned);
            }
        }
        this.operators = Map.copyOf(ops);
    }

    @Override
    public ProviderId id() {
        return ID;
    }

    @Override
    public Capabilities capabilities() {
        Set<Capability> caps = new HashSet<>();
        for (String m : COLLECT_METHODS) {
            caps.add(new Capability(Operation.COLLECT, "SN", m, XOF));
        }
        for (String m : operators.keySet()) {
            caps.add(new Capability(Operation.PAYOUT, "SN", m, XOF));
        }
        return new Capabilities(caps);
    }

    private Map<String, String> publicHeaders() {
        return Map.of("x-api-key", apiKey);
    }

    private Map<String, String> secretHeaders() {
        return Map.of("x-api-key", apiKey, "x-api-secret", apiSecret);
    }

    private URI path(String p) {
        return base.resolve(base.getPath() + p);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ collect

    @Override
    public CallOutcome collect(CollectRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reference", request.attemptReference());
        body.put("item_name", request.description() == null ? "Payment" : request.description());
        body.put("amount", request.amount().amount());
        body.put("currency", request.amount().currency().getCurrencyCode());
        body.put("countryISO", request.country());
        body.put("is_one_shot_payment", true);
        body.put("client_support_fee", true);
        if (request.callbackUrl() != null) {
            body.put("webhook_url", request.callbackUrl().toString());
        }
        if (request.returnUrl() != null) {
            body.put("success_url", request.returnUrl().toString());
            body.put("failure_url", request.returnUrl().toString());
        }
        // DexPay keys the session on our reference: even a lost answer can be looked up by it.
        ProviderReference ref = new ProviderReference(request.attemptReference());

        return switch (http.postJson(path("/checkout-sessions"), publicHeaders(), Json.write(body))) {
            case Result.NotSent n -> new CallOutcome.Rejected("PROVIDER_UNAVAILABLE", n.cause());
            case Result.Lost l -> new CallOutcome.Unknown(l.cause(), ref);
            case Result.Answered a when ProviderHttp.isServerError(a.status()) -> new CallOutcome.Unknown("HTTP " + a.status(), ref);
            case Result.Answered a when ProviderHttp.isSuccess(a.status()) -> {
                JsonNode data = dataOf(Json.parse(a.body()));
                Optional<String> url = Json.text(data, "payment_url").or(() -> Json.text(data, "checkout_url"));
                yield url.<CallOutcome>map(u -> new CallOutcome.Accepted(ref, URI.create(u), null))
                        .orElseGet(() -> new CallOutcome.Unknown("no payment_url in success response", ref));
            }
            case Result.Answered a -> new CallOutcome.Rejected("DEXPAY_HTTP_" + a.status(), message(a.body(), "Refused by DexPay"));
        };
    }

    @Override
    public StatusResult<PaymentStatus> status(ProviderReference payment) {
        if (!(http.get(path("/checkout-sessions/" + encode(payment.value())), secretHeaders()) instanceof Result.Answered a)
                || !ProviderHttp.isSuccess(a.status())) {
            return new StatusResult<>(null, null, null);
        }
        JsonNode data = dataOf(Json.parse(a.body()));
        String raw = Json.text(data, "status").orElse(null);
        return new StatusResult<>(DexPayStatus.checkout(raw), raw, amount(data));
    }

    // ------------------------------------------------------------------ payouts

    @Override
    public CallOutcome payout(PayoutRequest request) {
        if (request.recipientPhone() == null) {
            return new CallOutcome.Rejected("PHONE_REQUIRED", "DexPay pays out to a phone number (recipient.phone)");
        }
        String operator = operators.get(request.method());
        if (operator == null || !request.country().equals("SN")) {
            return new CallOutcome.Rejected("METHOD_NOT_SUPPORTED", "DexPay payout operator unknown for " + request.method() + " in " + request.country());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reference", request.attemptReference());
        body.put("amount", request.amount().amount());
        body.put("currency", request.amount().currency().getCurrencyCode());
        body.put("destination_phone", request.recipientPhone());
        body.put("destination_details", Map.of("operator", operator, "countryISO", request.country()));
        if (request.callbackUrl() != null) {
            body.put("webhook_url", request.callbackUrl().toString());
        }
        Map<String, String> headers = new HashMap<>(secretHeaders());
        headers.put("Idempotency-Key", request.attemptReference());
        ProviderReference ref = new ProviderReference(request.attemptReference());

        return switch (http.postJson(path("/payouts"), headers, Json.write(body))) {
            case Result.NotSent n -> new CallOutcome.Rejected("PROVIDER_UNAVAILABLE", n.cause());
            case Result.Lost l -> new CallOutcome.Unknown(l.cause(), ref);
            case Result.Answered a when ProviderHttp.isSuccess(a.status()) -> new CallOutcome.Accepted(ref, null, null);
            // Refused before any money moved.
            case Result.Answered a when a.status() == 400 || a.status() == 401 || a.status() == 402
                    || a.status() == 403 || a.status() == 422 ->
                    new CallOutcome.Rejected("DEXPAY_HTTP_" + a.status(), message(a.body(), "Refused by DexPay"));
            // 409 (reference exists with other details), 5xx, anything else: ask, don't guess.
            case Result.Answered a -> new CallOutcome.Unknown("HTTP " + a.status(), ref);
        };
    }

    @Override
    public StatusResult<PayoutStatus> payoutStatus(ProviderReference payout) {
        if (!(http.get(path("/payouts/" + encode(payout.value())), secretHeaders()) instanceof Result.Answered a)
                || !ProviderHttp.isSuccess(a.status())) {
            return new StatusResult<>(null, null, null);
        }
        JsonNode root = Json.parse(a.body());
        JsonNode data = root.has("payout") ? root.path("payout") : dataOf(root);
        String raw = Json.text(data, "status").orElse(null);
        return new StatusResult<>(DexPayStatus.payout(raw), raw, amount(data));
    }

    /** Checkout sessions and payouts are both keyed on Yoon's reference: found if DexPay knows it. */
    @Override
    public Optional<ProviderReference> lookup(Operation operation, String attemptReference) {
        String p = switch (operation) {
            case COLLECT -> "/checkout-sessions/";
            case PAYOUT -> "/payouts/";
            case REFUND -> null;
        };
        if (p == null) {
            return Optional.empty();
        }
        return http.get(path(p + encode(attemptReference)), secretHeaders()) instanceof Result.Answered a
                && ProviderHttp.isSuccess(a.status())
                ? Optional.of(new ProviderReference(attemptReference)) : Optional.empty();
    }

    // ------------------------------------------------------------------ refunds: none

    @Override
    public CallOutcome refund(RefundRequest request) {
        return new CallOutcome.Rejected("REFUND_NOT_SUPPORTED", "DexPay has no refund API");
    }

    @Override
    public StatusResult<RefundStatus> refundStatus(ProviderReference refund) {
        return new StatusResult<>(null, null, null);
    }

    // ------------------------------------------------------------------ callbacks

    @Override
    public WebhookVerification verify(InboundWebhook hook) {
        String sent = Optional.ofNullable(hook.header("x-webhook-signature"))
                .or(() -> Optional.ofNullable(hook.header("x-dexchange-signature")))
                .orElse(null);
        if (sent != null && sent.startsWith("sha256=")) {
            sent = sent.substring(7);
        }
        boolean valid = Signatures.hexEquals(Signatures.hmacSha256Hex(webhookSecret, hook.rawBody()), sent);

        JsonNode root = Json.parse(new String(hook.rawBody(), StandardCharsets.UTF_8));
        JsonNode data = dataOf(root);
        String ref = Json.text(data, "reference")
                .or(() -> Json.text(data, "merchant_reference"))
                .or(() -> Json.text(root, "reference"))
                .orElse(null);
        return new WebhookVerification(valid, ref == null ? null : new ProviderReference(ref));
    }

    // ------------------------------------------------------------------ helpers

    private static JsonNode dataOf(JsonNode root) {
        return root.has("data") && root.path("data").isObject() ? root.path("data") : root;
    }

    private static Money amount(JsonNode data) {
        String currency = Json.text(data, "currency").orElse("XOF");
        return Json.wholeAmount(data, "amount").map(v -> new Money(v, Currency.getInstance(currency))).orElse(null);
    }

    private static String message(String body, String fallback) {
        JsonNode json = Json.parse(body);
        return Json.text(json, "message").or(() -> Json.text(json, "error.message")).or(() -> Json.text(json, "error"))
                .orElse(fallback);
    }
}
