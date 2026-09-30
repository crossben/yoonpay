package dev.yoonpay.provider.paydunya;

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
import dev.yoonpay.provider.support.Bodies;
import dev.yoonpay.provider.support.Credentials;
import dev.yoonpay.provider.support.Json;
import dev.yoonpay.provider.support.Phones;
import dev.yoonpay.provider.support.ProviderHttp;
import dev.yoonpay.provider.support.ProviderHttp.Result;
import dev.yoonpay.provider.support.Signatures;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * PayDunya (Senegal): hosted checkout for collections, "disburse" for payouts. No refund API —
 * refunds are sent as payouts by the application.
 *
 * <p>Credentials: {@code master-key}, {@code private-key}, {@code token}, {@code mode}
 * ({@code test} or {@code live}; default test). Optional {@code store-name}.
 *
 * <p>Lessons carried over from production integrations:
 * <ul>
 *   <li>The IPN "hash" is {@code sha512(master key)} — a constant, so anyone who saw one callback
 *       can replay it. It is checked, but only the confirm API is trusted.</li>
 *   <li>Success is HTTP 2xx <em>and</em> {@code response_code == "00"}; the checkout URL is in
 *       {@code response_text}.</li>
 *   <li>Payouts are two calls: {@code get-invoice} moves no money (safe to fail), then
 *       {@code submit-invoice} does. A lost submit is unknown, never failed; the disburse token
 *       from step one is kept so the status can be asked.</li>
 *   <li>{@code account_alias} is the local number without {@code 221}; minimum 200 XOF; the
 *       token is {@code disburse_token}, sometimes {@code token}.</li>
 * </ul>
 */
public final class PayDunyaProvider implements PaymentProvider {

    static final ProviderId ID = new ProviderId("paydunya");
    static final long MIN_PAYOUT_XOF = 200;
    private static final Currency XOF = Currency.getInstance("XOF");

    /** Yoon method → PayDunya disburse {@code withdraw_mode} (Senegal). */
    static final Map<String, String> WITHDRAW_MODES = Map.of(
            "wave", "wave-senegal",
            "orange_money", "orange-money-senegal",
            "free_money", "free-money-senegal");

    private final ProviderHttp http;
    private final Map<String, String> headers;
    private final String masterKey;
    private final String storeName;
    private final URI checkoutBase;
    private final URI disburseBase;

    PayDunyaProvider(Credentials credentials, ProviderHttp http) {
        this.http = http;
        this.masterKey = credentials.require("master-key");
        this.headers = Map.of(
                "PAYDUNYA-MASTER-KEY", masterKey,
                "PAYDUNYA-PRIVATE-KEY", credentials.require("private-key"),
                "PAYDUNYA-TOKEN", credentials.require("token"));
        this.storeName = credentials.optional("store-name", "Yoon");
        boolean live = credentials.optional("mode", "test").equalsIgnoreCase("live");
        this.checkoutBase = URI.create(credentials.optional("base-url",
                live ? "https://app.paydunya.com/api/v1" : "https://app.paydunya.com/sandbox-api/v1"));
        // Disburse has no sandbox host.
        this.disburseBase = URI.create(credentials.optional("disburse-base-url", "https://app.paydunya.com/api/v2"));
    }

    @Override
    public ProviderId id() {
        return ID;
    }

    @Override
    public Capabilities capabilities() {
        Set<Capability> caps = new java.util.HashSet<>();
        for (String m : Set.of("wave", "orange_money", "free_money", "card")) {
            caps.add(new Capability(Operation.COLLECT, "SN", m, XOF));
        }
        for (String m : WITHDRAW_MODES.keySet()) {
            caps.add(new Capability(Operation.PAYOUT, "SN", m, XOF));
        }
        return new Capabilities(caps);
    }

    // ------------------------------------------------------------------ collect

    @Override
    public CallOutcome collect(CollectRequest request) {
        String description = request.description() == null ? "Payment" : request.description();
        Map<String, Object> invoice = new LinkedHashMap<>();
        invoice.put("total_amount", request.amount().amount());
        invoice.put("description", description);
        Map<String, Object> actions = new LinkedHashMap<>();
        if (request.callbackUrl() != null) {
            actions.put("callback_url", request.callbackUrl().toString());
        }
        if (request.returnUrl() != null) {
            actions.put("return_url", request.returnUrl().toString());
            actions.put("cancel_url", request.returnUrl().toString());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("invoice", invoice);
        body.put("store", Map.of("name", storeName));
        body.put("custom_data", Map.of("yoon_attempt", request.attemptReference()));
        body.put("actions", actions);

        return switch (http.postJson(checkoutBase.resolve(checkoutBase.getPath() + "/checkout-invoice/create"), headers, Json.write(body))) {
            case Result.NotSent n -> new CallOutcome.Rejected("PROVIDER_UNAVAILABLE", n.cause());
            case Result.Lost l -> new CallOutcome.Unknown(l.cause());
            case Result.Answered a when ProviderHttp.isServerError(a.status()) -> new CallOutcome.Unknown("HTTP " + a.status());
            case Result.Answered a -> {
                JsonNode json = Json.parse(a.body());
                String code = Json.text(json, "response_code").orElse(null);
                String token = Json.text(json, "token").orElse(null);
                String text = Json.text(json, "response_text").orElse("");
                if (ProviderHttp.isSuccess(a.status()) && "00".equals(code) && token != null && text.startsWith("http")) {
                    yield new CallOutcome.Accepted(new ProviderReference(token), URI.create(text), null);
                }
                if (ProviderHttp.isSuccess(a.status()) && code == null) {
                    // 2xx without the expected shape: we cannot tell what happened.
                    yield new CallOutcome.Unknown("unreadable success response");
                }
                yield new CallOutcome.Rejected("PAYDUNYA_" + (code == null ? "HTTP_" + a.status() : code),
                        text.isBlank() ? "Refused by PayDunya" : text);
            }
        };
    }

    @Override
    public StatusResult<PaymentStatus> status(ProviderReference payment) {
        URI uri = checkoutBase.resolve(checkoutBase.getPath() + "/checkout-invoice/confirm/"
                + URLEncoder.encode(payment.value(), StandardCharsets.UTF_8));
        if (!(http.get(uri, headers) instanceof Result.Answered a) || !ProviderHttp.isSuccess(a.status())) {
            return new StatusResult<>(null, null, null);
        }
        JsonNode json = Json.parse(a.body());
        String raw = Json.text(json, "status").orElse(null);
        Money amount = Json.wholeAmount(json, "invoice.total_amount").map(v -> new Money(v, XOF)).orElse(null);
        return new StatusResult<>(PayDunyaStatus.invoice(raw), raw, amount);
    }

    // ------------------------------------------------------------------ payouts

    @Override
    public CallOutcome payout(PayoutRequest request) {
        if (request.recipientPhone() == null) {
            return new CallOutcome.Rejected("PHONE_REQUIRED", "PayDunya pays out to a phone number (recipient.phone)");
        }
        String mode = WITHDRAW_MODES.get(request.method());
        if (mode == null || !request.country().equals("SN")) {
            return new CallOutcome.Rejected("METHOD_NOT_SUPPORTED", "PayDunya cannot pay out by " + request.method() + " in " + request.country());
        }
        if (request.amount().amount() < MIN_PAYOUT_XOF) {
            return new CallOutcome.Rejected("AMOUNT_TOO_LOW", "PayDunya pays out at least " + MIN_PAYOUT_XOF + " XOF");
        }

        // Step 1 — prepare. Moves no money: any failure here is a definite "not sent".
        Map<String, Object> prepare = new LinkedHashMap<>();
        prepare.put("account_alias", Phones.national(request.recipientPhone(), request.country()));
        prepare.put("amount", request.amount().amount());
        prepare.put("withdraw_mode", mode);
        prepare.put("disburse_id", request.attemptReference());
        if (request.callbackUrl() != null) {
            prepare.put("callback_url", request.callbackUrl().toString());
        }
        String token;
        switch (http.postJson(disburse("/disburse/get-invoice"), headers, Json.write(prepare))) {
            case Result.NotSent n -> {
                return new CallOutcome.Rejected("PROVIDER_UNAVAILABLE", n.cause());
            }
            case Result.Lost l -> {
                return new CallOutcome.Rejected("PROVIDER_UNAVAILABLE", "no answer to get-invoice (no money moved): " + l.cause());
            }
            case Result.Answered a -> {
                JsonNode json = Json.parse(a.body());
                String code = Json.text(json, "response_code").orElse(null);
                token = Json.text(json, "disburse_token").or(() -> Json.text(json, "token")).orElse(null);
                if (!ProviderHttp.isSuccess(a.status()) || !"00".equals(code) || token == null) {
                    return new CallOutcome.Rejected("PAYDUNYA_" + (code == null ? "HTTP_" + a.status() : code),
                            Json.text(json, "response_text").orElse("PayDunya refused the payout"));
                }
            }
        }

        // Step 2 — submit. This one moves money: a lost answer is unknown, and we keep the token.
        ProviderReference ref = new ProviderReference(token);
        Map<String, Object> submit = Map.of("disburse_invoice", token, "disburse_id", request.attemptReference());
        return switch (http.postJson(disburse("/disburse/submit-invoice"), headers, Json.write(submit))) {
            case Result.NotSent n -> new CallOutcome.Rejected("PROVIDER_UNAVAILABLE", n.cause());
            case Result.Lost l -> new CallOutcome.Unknown(l.cause(), ref);
            case Result.Answered a when ProviderHttp.isServerError(a.status()) -> new CallOutcome.Unknown("HTTP " + a.status(), ref);
            case Result.Answered a -> {
                JsonNode json = Json.parse(a.body());
                String code = Json.text(json, "response_code").orElse(null);
                if (ProviderHttp.isSuccess(a.status()) && "00".equals(code)) {
                    yield new CallOutcome.Accepted(ref, null, null);
                }
                if (code == null) {
                    yield new CallOutcome.Unknown("unreadable submit response (HTTP " + a.status() + ")", ref);
                }
                yield new CallOutcome.Rejected("PAYDUNYA_" + code,
                        Json.text(json, "response_text").or(() -> Json.text(json, "description")).orElse("PayDunya refused the payout"));
            }
        };
    }

    @Override
    public StatusResult<PayoutStatus> payoutStatus(ProviderReference payout) {
        if (!(http.postJson(disburse("/disburse/check-status"), headers, Json.write(Map.of("disburse_invoice", payout.value())))
                instanceof Result.Answered a) || !ProviderHttp.isSuccess(a.status())) {
            return new StatusResult<>(null, null, null);
        }
        JsonNode json = Json.parse(a.body());
        String raw = Json.text(json, "status").orElse(null);
        Money amount = Json.wholeAmount(json, "amount").map(v -> new Money(v, XOF)).orElse(null);
        return new StatusResult<>(PayDunyaStatus.disburse(raw), raw, amount);
    }

    private URI disburse(String path) {
        return disburseBase.resolve(disburseBase.getPath() + path);
    }

    // ------------------------------------------------------------------ refunds: none

    @Override
    public CallOutcome refund(RefundRequest request) {
        return new CallOutcome.Rejected("REFUND_NOT_SUPPORTED", "PayDunya has no refund API");
    }

    @Override
    public StatusResult<RefundStatus> refundStatus(ProviderReference refund) {
        return new StatusResult<>(null, null, null);
    }

    // ------------------------------------------------------------------ callbacks

    /**
     * Checkout IPNs carry {@code data.invoice.token}; disburse callbacks {@code data.token} or
     * {@code disburse_invoice}, sometimes flat and unsigned. The body may be JSON or a form, and
     * {@code data} may itself be a JSON string.
     */
    @Override
    public WebhookVerification verify(InboundWebhook hook) {
        JsonNode root = Bodies.parse(hook.rawBody(), hook.header("Content-Type"));
        JsonNode data = root.path("data");
        if (data.isString()) {
            data = Json.parse(data.asString());
        }
        String hash = Json.text(data, "hash").or(() -> Json.text(root, "hash")).orElse(null);
        boolean valid = Signatures.hexEquals(Signatures.sha512Hex(masterKey), hash);

        JsonNode d = data.isMissingNode() ? root : data;
        String ref = Json.text(d, "invoice.token")
                .or(() -> Json.text(d, "token"))
                .or(() -> Json.text(d, "disburse_invoice"))
                .orElse(null);
        return new WebhookVerification(valid, ref == null ? null : new ProviderReference(ref));
    }
}
