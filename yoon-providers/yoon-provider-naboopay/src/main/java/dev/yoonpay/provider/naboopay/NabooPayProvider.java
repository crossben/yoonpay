package dev.yoonpay.provider.naboopay;

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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * NabooPay (Senegal): hosted checkout for collections only.
 *
 * <p>Not supported in v1: payouts (the cashout API has no idempotency key and no callback, so a
 * lost answer could pay twice) and refunds (no API).
 *
 * <p>Credentials: {@code api-key}, {@code webhook-secret}. There is no sandbox host; test vs live
 * is decided by the key.
 *
 * <p>Lessons carried over: the wallet is sent from a fixed table (an upper-cased slug once sent
 * {@code ORANGE-MONEY} and only Wave worked); amounts are JSON integers; the API takes no
 * merchant reference, so {@code order_id} is the only link — a lost create answer cannot be looked
 * up (the customer never received a checkout link, so nothing can have been paid); callbacks are
 * HMAC-SHA256 of the raw body in {@code X-Signature}.
 */
public final class NabooPayProvider implements PaymentProvider {

    static final ProviderId ID = new ProviderId("naboopay");
    private static final Currency XOF = Currency.getInstance("XOF");

    /** Yoon method → NabooPay wallet, exactly as NabooPay spells it. */
    static final Map<String, String> WALLETS = Map.of(
            "wave", "WAVE",
            "orange_money", "ORANGE_MONEY",
            "free_money", "FREE_MONEY",
            "card", "BANK");

    private final ProviderHttp http;
    private final Map<String, String> headers;
    private final String webhookSecret;
    private final URI base;

    NabooPayProvider(Credentials credentials, ProviderHttp http) {
        this.http = http;
        this.headers = Map.of("Authorization", "Bearer " + credentials.require("api-key"));
        this.webhookSecret = credentials.require("webhook-secret");
        this.base = URI.create(credentials.optional("base-url", "https://api.naboopay.com/api/v1"));
    }

    @Override
    public ProviderId id() {
        return ID;
    }

    @Override
    public Capabilities capabilities() {
        Set<Capability> caps = new HashSet<>();
        for (String m : WALLETS.keySet()) {
            caps.add(new Capability(Operation.COLLECT, "SN", m, XOF));
        }
        return new Capabilities(caps);
    }

    private URI path(String p) {
        return base.resolve(base.getPath() + p);
    }

    @Override
    public CallOutcome collect(CollectRequest request) {
        String wallet = WALLETS.get(request.method());
        if (wallet == null) {
            return new CallOutcome.Rejected("METHOD_NOT_SUPPORTED", "NabooPay has no wallet for " + request.method());
        }
        if (request.amount().currency().getDefaultFractionDigits() != 0) {
            // NabooPay takes whole units: sending minor units would overcharge 100x, truncating would undercharge.
            return new CallOutcome.Rejected("CURRENCY_NOT_SUPPORTED", "NabooPay takes whole-unit currencies only");
        }
        String name = request.description() == null ? "Payment" : request.description();
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("name", name);
        product.put("category", "payment");
        product.put("amount", request.amount().amount());
        product.put("quantity", 1);
        product.put("description", name);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("method_of_payment", List.of(wallet));
        body.put("products", List.of(product));
        if (request.returnUrl() != null) {
            body.put("success_url", request.returnUrl().toString());
            body.put("error_url", request.returnUrl().toString());
        }
        body.put("fees_customer_side", true);

        return switch (http.postJson(path("/transaction/create-transaction"), headers, Json.write(body))) {
            case Result.NotSent n -> new CallOutcome.Rejected("PROVIDER_UNAVAILABLE", n.cause());
            case Result.Lost l -> new CallOutcome.Unknown(l.cause());
            case Result.Answered a when ProviderHttp.isServerError(a.status()) -> new CallOutcome.Unknown("HTTP " + a.status());
            case Result.Answered a when ProviderHttp.isSuccess(a.status()) -> {
                JsonNode json = Json.parse(a.body());
                var orderId = Json.text(json, "order_id");
                var url = Json.text(json, "checkout_url");
                yield orderId.isPresent() && url.isPresent()
                        ? new CallOutcome.Accepted(new ProviderReference(orderId.get()), URI.create(url.get()), null)
                        : new CallOutcome.Unknown("success without order_id/checkout_url");
            }
            case Result.Answered a -> new CallOutcome.Rejected("NABOOPAY_HTTP_" + a.status(), message(a.body()));
        };
    }

    @Override
    public StatusResult<PaymentStatus> status(ProviderReference payment) {
        URI uri = path("/transaction/get-one-transaction?order_id=" + URLEncoder.encode(payment.value(), StandardCharsets.UTF_8));
        if (!(http.get(uri, headers) instanceof Result.Answered a) || !ProviderHttp.isSuccess(a.status())) {
            return new StatusResult<>(null, null, null);
        }
        JsonNode json = Json.parse(a.body());
        String raw = Json.text(json, "transaction_status").orElse(null);
        PaymentStatus status = NabooPayStatus.of(raw);
        // Only a paid transaction's amount is a confirmation; part_paid never settles.
        Money amount = status == PaymentStatus.SUCCEEDED
                ? Json.wholeAmount(json, "amount").map(v -> new Money(v,
                        Currency.getInstance(Json.text(json, "currency").orElse("XOF")))).orElse(null)
                : null;
        return new StatusResult<>(status, raw, amount);
    }

    @Override
    public WebhookVerification verify(InboundWebhook hook) {
        boolean valid = Signatures.hexEquals(Signatures.hmacSha256Hex(webhookSecret, hook.rawBody()), hook.header("X-Signature"));
        String ref = Json.text(Json.parse(new String(hook.rawBody(), StandardCharsets.UTF_8)), "order_id").orElse(null);
        return new WebhookVerification(valid, ref == null ? null : new ProviderReference(ref));
    }

    // ------------------------------------------------------------------ not supported in v1

    @Override
    public CallOutcome refund(RefundRequest request) {
        return new CallOutcome.Rejected("REFUND_NOT_SUPPORTED", "NabooPay has no refund API");
    }

    @Override
    public StatusResult<RefundStatus> refundStatus(ProviderReference refund) {
        return new StatusResult<>(null, null, null);
    }

    @Override
    public CallOutcome payout(PayoutRequest request) {
        return new CallOutcome.Rejected("PAYOUT_NOT_SUPPORTED", "NabooPay payouts are not supported: no idempotency key, no callback");
    }

    @Override
    public StatusResult<PayoutStatus> payoutStatus(ProviderReference payout) {
        return new StatusResult<>(null, null, null);
    }

    /** NabooPay's FastAPI errors: {@code detail} as a string or a list of {@code {msg}}. */
    private static String message(String body) {
        JsonNode json = Json.parse(body);
        JsonNode detail = json.path("detail");
        if (detail.isArray() && !detail.isEmpty()) {
            return Json.text(detail.get(0), "msg").orElse("Refused by NabooPay");
        }
        return Json.text(json, "detail").or(() -> Json.text(json, "message")).orElse("Refused by NabooPay");
    }
}
