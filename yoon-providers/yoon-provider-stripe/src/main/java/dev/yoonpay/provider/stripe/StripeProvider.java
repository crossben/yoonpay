package dev.yoonpay.provider.stripe;

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
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Stripe Checkout for cards: hosted Checkout Sessions, full and partial refunds. No payouts
 * (Stripe pays out to the merchant's own bank, not to recipients). See ADR-0022.
 *
 * <p>Credentials: {@code secret-key} ({@code sk_test_…} or {@code sk_live_…}, or a restricted
 * key with Checkout Sessions and Refunds write access), {@code webhook-secret} ({@code whsec_…}),
 * {@code countries} (comma-separated ISO codes the app sells to, default the eight UEMOA states),
 * {@code currencies} (default {@code XOF}), {@code base-url} (default {@code https://api.stripe.com}).
 *
 * <p>Every create sends Yoon's reference as {@code Idempotency-Key}, so a repeated request never
 * creates a second session or refund.
 */
public final class StripeProvider implements PaymentProvider {

    static final ProviderId ID = new ProviderId("stripe");
    static final String METHOD = "card";
    /**
     * Currencies whose minor unit is the same in Yoon (ISO 4217) and in Stripe. Stripe treats some
     * currencies differently (e.g. ISK, UGX, HUF, TWD); they are refused rather than mis-scaled.
     */
    private static final Set<String> SAFE_CURRENCIES = Set.of("XOF", "XAF", "EUR", "USD", "GBP", "CAD", "CHF");
    private static final String UEMOA = "BJ,BF,CI,GW,ML,NE,SN,TG";

    private final ProviderHttp http;
    private final String secretKey;
    private final String webhookSecret;
    private final URI base;
    private final Set<String> countries;
    private final Set<Currency> currencies;

    StripeProvider(Credentials credentials, ProviderHttp http) {
        this.http = http;
        this.secretKey = credentials.require("secret-key");
        this.webhookSecret = credentials.require("webhook-secret");
        this.base = URI.create(credentials.optional("base-url", "https://api.stripe.com"));
        Set<String> cs = new HashSet<>();
        for (String c : credentials.optional("countries", UEMOA).split(",")) {
            String code = c.trim().toUpperCase(Locale.ROOT);
            if (!code.matches("[A-Z]{2}")) {
                throw new IllegalArgumentException("stripe: invalid country '" + code + "'");
            }
            cs.add(code);
        }
        this.countries = Set.copyOf(cs);
        Set<Currency> cur = new HashSet<>();
        for (String c : credentials.optional("currencies", "XOF").split(",")) {
            String code = c.trim().toUpperCase(Locale.ROOT);
            if (!SAFE_CURRENCIES.contains(code)) {
                throw new IllegalArgumentException("stripe: currency '" + code + "' not supported (supported: " + SAFE_CURRENCIES + ")");
            }
            cur.add(Currency.getInstance(code));
        }
        this.currencies = Set.copyOf(cur);
    }

    @Override
    public ProviderId id() {
        return ID;
    }

    @Override
    public Capabilities capabilities() {
        Set<Capability> caps = new HashSet<>();
        for (String c : countries) {
            for (Currency cur : currencies) {
                caps.add(new Capability(Operation.COLLECT, c, METHOD, cur));
                caps.add(new Capability(Operation.REFUND, c, METHOD, cur));
            }
        }
        return new Capabilities(caps);
    }

    private Map<String, String> headers(String idempotencyKey) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + secretKey);
        if (idempotencyKey != null) {
            h.put("Idempotency-Key", idempotencyKey);
        }
        return h;
    }

    private URI path(String p) {
        return base.resolve(base.getPath() + p);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private JsonNode read(String p) {
        if (!(http.get(path(p), headers(null)) instanceof Result.Answered a) || !ProviderHttp.isSuccess(a.status())) {
            return Json.parse(null);
        }
        return Json.parse(a.body());
    }

    // ------------------------------------------------------------------ collect

    @Override
    public CallOutcome collect(CollectRequest request) {
        if (request.returnUrl() == null) {
            return new CallOutcome.Rejected("RETURN_URL_REQUIRED", "Stripe Checkout needs return_url");
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("mode", "payment");
        form.put("success_url", request.returnUrl().toString());
        form.put("cancel_url", request.returnUrl().toString());
        form.put("client_reference_id", request.attemptReference());
        form.put("metadata[yoon_reference]", request.attemptReference());
        form.put("payment_intent_data[metadata][yoon_reference]", request.attemptReference());
        form.put("line_items[0][quantity]", "1");
        form.put("line_items[0][price_data][currency]", request.amount().currency().getCurrencyCode().toLowerCase(Locale.ROOT));
        form.put("line_items[0][price_data][unit_amount]", Long.toString(request.amount().amount()));
        form.put("line_items[0][price_data][product_data][name]",
                request.description() == null || request.description().isBlank() ? "Payment" : request.description());

        return switch (http.postForm(path("/v1/checkout/sessions"), headers(request.attemptReference()), form)) {
            case Result.NotSent n -> new CallOutcome.Rejected("PROVIDER_UNAVAILABLE", n.cause());
            // No money moves when a session is created, and the customer never saw its URL.
            case Result.Lost l -> new CallOutcome.Unknown(l.cause());
            case Result.Answered a when ProviderHttp.isServerError(a.status()) -> new CallOutcome.Unknown("HTTP " + a.status());
            case Result.Answered a when ProviderHttp.isSuccess(a.status()) -> {
                JsonNode json = Json.parse(a.body());
                Optional<String> id = Json.text(json, "id");
                Optional<String> url = Json.text(json, "url");
                yield id.isPresent() && url.isPresent()
                        ? new CallOutcome.Accepted(new ProviderReference(id.get()), URI.create(url.get()), null)
                        : new CallOutcome.Unknown("no id or url in success response");
            }
            case Result.Answered a when a.status() == 409 -> new CallOutcome.Unknown("idempotency conflict (HTTP 409)");
            case Result.Answered a -> new CallOutcome.Rejected(code(a), message(a.body()));
        };
    }

    @Override
    public StatusResult<PaymentStatus> status(ProviderReference payment) {
        JsonNode json = read("/v1/checkout/sessions/" + encode(payment.value()));
        String status = Json.text(json, "status").orElse(null);
        String paymentStatus = Json.text(json, "payment_status").orElse(null);
        String raw = status == null ? null : status + "/" + paymentStatus;
        return new StatusResult<>(StripeStatus.checkout(status, paymentStatus), raw, amount(json, "amount_total"));
    }

    // ------------------------------------------------------------------ refunds

    @Override
    public CallOutcome refund(RefundRequest request) {
        Optional<String> paymentIntent = Json.text(read("/v1/checkout/sessions/" + encode(request.payment().value())), "payment_intent");
        if (paymentIntent.isEmpty()) {
            return new CallOutcome.Rejected("PAYMENT_NOT_FOUND", "Stripe has no paid payment for this Checkout Session");
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("payment_intent", paymentIntent.get());
        form.put("amount", Long.toString(request.amount().amount()));
        form.put("metadata[yoon_reference]", request.attemptReference());

        return switch (http.postForm(path("/v1/refunds"), headers(request.attemptReference()), form)) {
            case Result.NotSent n -> new CallOutcome.Rejected("PROVIDER_UNAVAILABLE", n.cause());
            case Result.Lost l -> new CallOutcome.Unknown(l.cause());
            case Result.Answered a when ProviderHttp.isSuccess(a.status()) -> Json.text(Json.parse(a.body()), "id")
                    .<CallOutcome>map(id -> new CallOutcome.Accepted(new ProviderReference(id), null, null))
                    .orElseGet(() -> new CallOutcome.Unknown("no id in success response"));
            // Refused before any money moved (validation, auth, amount above what is left, rate limit).
            case Result.Answered a when a.status() == 400 || a.status() == 401 || a.status() == 402
                    || a.status() == 403 || a.status() == 404 || a.status() == 429 ->
                    new CallOutcome.Rejected(code(a), message(a.body()));
            // 409 (idempotency conflict), 5xx, anything else: ask, don't guess.
            case Result.Answered a -> new CallOutcome.Unknown("HTTP " + a.status());
        };
    }

    @Override
    public StatusResult<RefundStatus> refundStatus(ProviderReference refund) {
        JsonNode json = read("/v1/refunds/" + encode(refund.value()));
        String raw = Json.text(json, "status").orElse(null);
        return new StatusResult<>(StripeStatus.refund(raw), raw, amount(json, "amount"));
    }

    // ------------------------------------------------------------------ payouts: none

    @Override
    public CallOutcome payout(PayoutRequest request) {
        return new CallOutcome.Rejected("PAYOUT_NOT_SUPPORTED", "Stripe pays out to your own bank account, not to recipients");
    }

    @Override
    public StatusResult<PayoutStatus> payoutStatus(ProviderReference payout) {
        return new StatusResult<>(null, null, null);
    }

    /**
     * A lost refund answer is found among recent refunds by our reference in metadata. A lost
     * Checkout Session needs no lookup: no money moves until the customer opens its URL, which
     * they never received.
     */
    @Override
    public Optional<ProviderReference> lookup(Operation operation, String attemptReference) {
        if (operation != Operation.REFUND) {
            return Optional.empty();
        }
        JsonNode data = read("/v1/refunds?limit=100").path("data");
        List<String> found = new ArrayList<>();
        for (JsonNode r : data) {
            if (attemptReference.equals(Json.text(r, "metadata.yoon_reference").orElse(null))) {
                Json.text(r, "id").ifPresent(found::add);
            }
        }
        return found.size() == 1 ? Optional.of(new ProviderReference(found.getFirst())) : Optional.empty();
    }

    // ------------------------------------------------------------------ callbacks

    /**
     * {@code Stripe-Signature: t=<time>,v1=<hex>[,v1=…][,v0=…]}: HMAC-SHA256 of {@code t + "." + body};
     * only {@code v1} counts (v0 is a test-only scheme, ignored against downgrade). No timestamp
     * window: Yoon verifies stored callbacks later, and a replay changes nothing because the status
     * API decides.
     */
    @Override
    public WebhookVerification verify(InboundWebhook hook) {
        byte[] body = hook.rawBody();
        boolean valid = signatureValid(hook.header("stripe-signature"), body);
        String ref = Json.text(Json.parse(new String(body, StandardCharsets.UTF_8)), "data.object.id").orElse(null);
        return new WebhookVerification(valid, ref == null ? null : new ProviderReference(ref));
    }

    private boolean signatureValid(String header, byte[] body) {
        if (header == null) {
            return false;
        }
        String t = null;
        List<String> v1 = new ArrayList<>();
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if (kv[0].equals("t")) {
                t = kv[1];
            } else if (kv[0].equals("v1")) {
                v1.add(kv[1]);
            }
        }
        if (t == null || v1.isEmpty()) {
            return false;
        }
        byte[] prefix = (t + ".").getBytes(StandardCharsets.UTF_8);
        byte[] signed = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, signed, 0, prefix.length);
        System.arraycopy(body, 0, signed, prefix.length, body.length);
        String expected = Signatures.hmacSha256Hex(webhookSecret, signed);
        return v1.stream().anyMatch(s -> Signatures.hexEquals(expected, s));
    }

    // ------------------------------------------------------------------ helpers

    private static Money amount(JsonNode json, String field) {
        Optional<String> currency = Json.text(json, "currency");
        Optional<Long> value = Json.wholeAmount(json, field);
        if (currency.isEmpty() || value.isEmpty()) {
            return null;
        }
        String code = currency.get().toUpperCase(Locale.ROOT);
        return SAFE_CURRENCIES.contains(code) ? new Money(value.get(), Currency.getInstance(code)) : null;
    }

    private static String code(Result.Answered a) {
        JsonNode e = Json.parse(a.body()).path("error");
        return Json.text(e, "code").or(() -> Json.text(e, "type"))
                .map(c -> "STRIPE_" + c.toUpperCase(Locale.ROOT))
                .orElse("STRIPE_HTTP_" + a.status());
    }

    private static String message(String body) {
        return Json.text(Json.parse(body), "error.message").orElse("Refused by Stripe");
    }
}
