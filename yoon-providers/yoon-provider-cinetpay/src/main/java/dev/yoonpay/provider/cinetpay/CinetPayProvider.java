package dev.yoonpay.provider.cinetpay;

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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CinetPay, through its API v1 (api.cinetpay.net in sandbox, api.cinetpay.co in production — the
 * API used by CinetPay's own 2026 SDKs): hosted payments and mobile-money transfers in several
 * West and Central African countries. No refund API. See ADR-0023.
 *
 * <p>Credentials: {@code countries} (comma-separated, e.g. {@code CI,SN}), and for each country
 * {@code api-key-<cc>} and {@code api-password-<cc>} (CinetPay issues one pair per country);
 * {@code customer-email} (CinetPay requires a customer e-mail and Yoon has none: give a contact
 * address of yours); {@code base-url} (default chosen from the key prefix: {@code sk_test_} →
 * sandbox, otherwise production).
 *
 * <p>CinetPay keys a transaction on the merchant's {@code merchant_transaction_id} (at most 30
 * characters), so lost answers are found by it and a duplicate is refused
 * ({@code TRANSACTION_EXIST}).
 */
public final class CinetPayProvider implements PaymentProvider {

    static final ProviderId ID = new ProviderId("cinetpay");

    /** Country → currency. Countries whose currency Yoon and CinetPay count differently are left out. */
    static final Map<String, String> CURRENCIES = Map.of(
            "CI", "XOF", "SN", "XOF", "BF", "XOF", "ML", "XOF", "TG", "XOF", "BJ", "XOF", "NE", "XOF",
            "CM", "XAF", "GN", "GNF");

    /** Yoon method → CinetPay operator prefix; the operator code is {@code <prefix>_<country>}. */
    static final Map<String, String> OPERATORS = Map.of(
            "orange_money", "OM", "wave", "WAVE", "free_money", "FREE", "moov", "MOOV", "mtn", "MTN",
            "expresso", "EXPRESSO", "tmoney", "TMONEY", "airtel", "AIRTEL", "zamani", "ZAMANI");

    /** Operators CinetPay offers per country (from its SDKs). */
    static final Map<String, Set<String>> OPERATORS_BY_COUNTRY = Map.of(
            "CI", Set.of("OM_CI", "MOOV_CI", "MTN_CI", "WAVE_CI"),
            "BF", Set.of("OM_BF", "MOOV_BF", "WAVE_BF"),
            "ML", Set.of("OM_ML", "MOOV_ML"),
            "SN", Set.of("OM_SN", "FREE_SN", "EXPRESSO_SN", "WAVE_SN"),
            "TG", Set.of("MOOV_TG", "TMONEY_TG"),
            "GN", Set.of("OM_GN", "MTN_GN"),
            "CM", Set.of("OM_CM", "MTN_CM"),
            "BJ", Set.of("MOOV_BJ", "MTN_BJ"),
            "NE", Set.of("AIRTEL_NE", "MOOV_NE", "ZAMANI_NE"));

    static final long MIN_PAYMENT = 100;
    static final long MAX_PAYMENT = 2_500_000;
    static final long MIN_TRANSFER = 500;
    static final long MAX_TRANSFER = 1_500_000;
    private static final Duration TOKEN_TTL = Duration.ofHours(12);

    private record Account(String apiKey, String apiPassword) {
    }

    private final ProviderHttp http;
    private final Map<String, Account> accounts;
    private final String customerEmail;
    private final URI base;
    private final Clock clock;
    private final Map<String, String> tokens = new ConcurrentHashMap<>();
    private final Map<String, Instant> tokenExpiry = new ConcurrentHashMap<>();

    CinetPayProvider(Credentials credentials, ProviderHttp http) {
        this(credentials, http, Clock.systemUTC());
    }

    CinetPayProvider(Credentials credentials, ProviderHttp http, Clock clock) {
        this.http = http;
        this.clock = clock;
        Map<String, Account> acc = new HashMap<>();
        for (String c : credentials.require("countries").split(",")) {
            String cc = c.trim().toUpperCase(Locale.ROOT);
            if (!CURRENCIES.containsKey(cc)) {
                throw new IllegalArgumentException("cinetpay: unsupported country '" + cc + "' (supported: " + CURRENCIES.keySet() + ")");
            }
            String lower = cc.toLowerCase(Locale.ROOT);
            acc.put(cc, new Account(credentials.require("api-key-" + lower), credentials.require("api-password-" + lower)));
        }
        this.accounts = Map.copyOf(acc);
        this.customerEmail = credentials.require("customer-email");
        boolean test = accounts.values().stream().allMatch(a -> a.apiKey().startsWith("sk_test_"));
        this.base = URI.create(credentials.optional("base-url", test ? "https://api.cinetpay.net" : "https://api.cinetpay.co"));
    }

    @Override
    public ProviderId id() {
        return ID;
    }

    @Override
    public Capabilities capabilities() {
        Set<Capability> caps = new HashSet<>();
        for (String cc : accounts.keySet()) {
            Currency cur = Currency.getInstance(CURRENCIES.get(cc));
            for (var e : OPERATORS.entrySet()) {
                if (OPERATORS_BY_COUNTRY.get(cc).contains(e.getValue() + "_" + cc)) {
                    caps.add(new Capability(Operation.COLLECT, cc, e.getKey(), cur));
                    caps.add(new Capability(Operation.PAYOUT, cc, e.getKey(), cur));
                }
            }
        }
        return new Capabilities(caps);
    }

    /** CinetPay's operator code for a Yoon method in a country, or null. */
    static String operator(String method, String country) {
        String prefix = OPERATORS.get(method);
        String code = prefix == null ? null : prefix + "_" + country;
        Set<String> offered = OPERATORS_BY_COUNTRY.get(country);
        return code != null && offered != null && offered.contains(code) ? code : null;
    }

    /**
     * CinetPay's {@code merchant_transaction_id} is at most 30 characters; Yoon's references are
     * longer. A longer reference maps to a fixed, collision-resistant 30-character id.
     */
    static String txId(String reference) {
        if (reference.length() <= 30) {
            return reference;
        }
        return "y" + Signatures.sha512Hex(reference).substring(0, 29);
    }

    // ------------------------------------------------------------------ auth

    /** A JWT for the country's account, cached; null when none could be had (nothing was sent). */
    private String token(String country) {
        // Map.copyOf refuses null keys: a reference without a country has no account.
        Account account = country == null ? null : accounts.get(country);
        if (account == null) {
            return null;
        }
        String cached = tokens.get(country);
        Instant expiry = tokenExpiry.get(country);
        if (cached != null && expiry != null && clock.instant().isBefore(expiry)) {
            return cached;
        }
        Map<String, String> body = new LinkedHashMap<>();
        body.put("api_key", account.apiKey());
        body.put("api_password", account.apiPassword());
        if (!(http.postJson(path("/v1/oauth/login"), Map.of(), Json.write(body)) instanceof Result.Answered a)
                || !ProviderHttp.isSuccess(a.status())) {
            return null;
        }
        Optional<String> t = Json.text(Json.parse(a.body()), "access_token");
        t.ifPresent(v -> {
            tokens.put(country, v);
            tokenExpiry.put(country, clock.instant().plus(TOKEN_TTL));
        });
        return t.orElse(null);
    }

    private void forgetToken(String country) {
        tokens.remove(country);
        tokenExpiry.remove(country);
    }

    private static Map<String, String> bearer(String token) {
        return Map.of("Authorization", "Bearer " + token);
    }

    private URI path(String p) {
        return base.resolve(base.getPath() + p);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** The country of a reference: references are {@code <cc>:<merchant_transaction_id>}. */
    private static String[] split(ProviderReference ref) {
        String v = ref.value();
        int i = v.indexOf(':');
        return i == 2 ? new String[]{v.substring(0, 2), v.substring(3)} : new String[]{null, v};
    }

    private JsonNode read(String country, String p) {
        String t = token(country);
        if (t == null || !(http.get(path(p), bearer(t)) instanceof Result.Answered a) || !ProviderHttp.isSuccess(a.status())) {
            return Json.parse(null);
        }
        return Json.parse(a.body());
    }

    // ------------------------------------------------------------------ collect

    @Override
    public CallOutcome collect(CollectRequest request) {
        String cc = request.country();
        if (!accounts.containsKey(cc)) {
            return new CallOutcome.Rejected("METHOD_NOT_SUPPORTED", "CinetPay is not configured for " + cc);
        }
        if (request.returnUrl() == null) {
            return new CallOutcome.Rejected("RETURN_URL_REQUIRED", "CinetPay needs return_url");
        }
        long amount = request.amount().amount();
        if (amount < MIN_PAYMENT || amount > MAX_PAYMENT) {
            return new CallOutcome.Rejected("AMOUNT_OUT_OF_RANGE", "CinetPay accepts " + MIN_PAYMENT + " to " + MAX_PAYMENT);
        }
        String t = token(cc);
        if (t == null) {
            return new CallOutcome.Rejected("PROVIDER_UNAVAILABLE", "no CinetPay access token");
        }
        String tx = txId(request.attemptReference());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("currency", request.amount().currency().getCurrencyCode());
        body.put("merchant_transaction_id", tx);
        body.put("amount", amount);
        body.put("lang", "fr");
        body.put("designation", request.description() == null || request.description().isBlank() ? "Paiement" : request.description());
        body.put("client_email", customerEmail);
        body.put("client_first_name", "Client");
        body.put("client_last_name", "Client");
        body.put("success_url", request.returnUrl().toString());
        body.put("failed_url", request.returnUrl().toString());
        body.put("notify_url", request.callbackUrl() == null ? request.returnUrl().toString() : request.callbackUrl().toString());
        body.put("channel", "PUSH");
        String op = operator(request.method(), cc);
        if (op != null) {
            body.put("payment_method", op);
        }
        if (request.customerPhone() != null) {
            body.put("client_phone_number", request.customerPhone());
        }
        ProviderReference ref = new ProviderReference(cc + ":" + tx);
        Result result = http.postJson(path("/v1/payment"), bearer(t), Json.write(body));
        if (result instanceof Result.Answered a && ProviderHttp.isSuccess(a.status()) && accepted(a)) {
            Optional<String> url = Json.text(Json.parse(a.body()), "payment_url");
            return url.<CallOutcome>map(u -> new CallOutcome.Accepted(ref, URI.create(u), null))
                    .orElseGet(() -> new CallOutcome.Unknown("no payment_url in success response", ref));
        }
        return failure(result, ref, cc);
    }

    @Override
    public StatusResult<PaymentStatus> status(ProviderReference payment) {
        String[] r = split(payment);
        JsonNode json = read(r[0], "/v1/payment/" + encode(r[1]));
        String raw = Json.text(json, "status").orElse(null);
        // CinetPay's payment status carries no amount; its hosted page charges the amount sent.
        return new StatusResult<>(CinetPayStatus.payment(raw), raw, null);
    }

    // ------------------------------------------------------------------ payouts (transfers)

    @Override
    public CallOutcome payout(PayoutRequest request) {
        String cc = request.country();
        String op = operator(request.method(), cc);
        if (!accounts.containsKey(cc) || op == null) {
            return new CallOutcome.Rejected("METHOD_NOT_SUPPORTED", "CinetPay cannot pay out by " + request.method() + " in " + cc);
        }
        if (request.recipientPhone() == null) {
            return new CallOutcome.Rejected("PHONE_REQUIRED", "CinetPay pays out to a phone number (recipient.phone)");
        }
        long amount = request.amount().amount();
        if (amount < MIN_TRANSFER || amount > MAX_TRANSFER) {
            return new CallOutcome.Rejected("AMOUNT_OUT_OF_RANGE", "CinetPay transfers " + MIN_TRANSFER + " to " + MAX_TRANSFER);
        }
        String t = token(cc);
        if (t == null) {
            return new CallOutcome.Rejected("PROVIDER_UNAVAILABLE", "no CinetPay access token");
        }
        String tx = txId(request.attemptReference());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("currency", request.amount().currency().getCurrencyCode());
        body.put("merchant_transaction_id", tx);
        body.put("phone_number", request.recipientPhone());
        body.put("amount", amount);
        body.put("payment_method", op);
        body.put("reason", "Payout");
        body.put("notify_url", request.callbackUrl() == null ? "https://localhost/" : request.callbackUrl().toString());
        ProviderReference ref = new ProviderReference(cc + ":" + tx);
        Result result = http.postJson(path("/v1/transfer"), bearer(t), Json.write(body));
        if (result instanceof Result.Answered a && ProviderHttp.isSuccess(a.status())) {
            JsonNode json = Json.parse(a.body());
            if (accepted(a)) {
                return new CallOutcome.Accepted(ref, null, null);
            }
            // A definite FAILED on creation moved nothing; anything else is asked again later.
            if ("FAILED".equals(Json.text(json, "status").orElse(null))) {
                return new CallOutcome.Rejected("CINETPAY_FAILED", message(a.body()));
            }
        }
        return failure(result, ref, cc);
    }

    @Override
    public StatusResult<PayoutStatus> payoutStatus(ProviderReference payout) {
        String[] r = split(payout);
        JsonNode json = read(r[0], "/v1/transfer/" + encode(r[1]));
        String raw = Json.text(json, "status").orElse(null);
        Money amount = Json.wholeAmount(json, "amount")
                .flatMap(v -> Json.text(json, "currency").map(c -> new Money(v, Currency.getInstance(c))))
                .orElse(null);
        return new StatusResult<>(CinetPayStatus.transfer(raw), raw, amount);
    }

    /** Both payments and transfers are found by our merchant_transaction_id, in any configured country. */
    @Override
    public Optional<ProviderReference> lookup(Operation operation, String attemptReference) {
        String p = switch (operation) {
            case COLLECT -> "/v1/payment/";
            case PAYOUT -> "/v1/transfer/";
            case REFUND -> null;
        };
        if (p == null) {
            return Optional.empty();
        }
        String tx = txId(attemptReference);
        for (String cc : accounts.keySet()) {
            JsonNode json = read(cc, p + encode(tx));
            if (tx.equals(Json.text(json, "merchant_transaction_id").orElse(null))) {
                return Optional.of(new ProviderReference(cc + ":" + tx));
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ refunds: none

    @Override
    public CallOutcome refund(RefundRequest request) {
        return new CallOutcome.Rejected("REFUND_NOT_SUPPORTED", "CinetPay has no refund API");
    }

    @Override
    public StatusResult<RefundStatus> refundStatus(ProviderReference refund) {
        return new StatusResult<>(null, null, null);
    }

    // ------------------------------------------------------------------ callbacks

    /**
     * CinetPay authenticates a notification with the {@code notify_token} returned when the
     * transaction was created, not with a signature Yoon could check here. Notifications are
     * therefore never trusted: they are reported invalid, and the reconciliation sweep settles
     * CinetPay transactions through the status API (ADR-0023).
     */
    @Override
    public WebhookVerification verify(InboundWebhook hook) {
        return new WebhookVerification(false, null);
    }

    // ------------------------------------------------------------------ helpers

    /** HTTP 2xx and CinetPay's body code says the request was taken. */
    private static boolean accepted(Result.Answered a) {
        JsonNode json = Json.parse(a.body());
        Optional<Long> code = Json.wholeAmount(json, "code");
        return code.isPresent() && (code.get() == 200 || code.get() == 100 || code.get() == 2001 || code.get() == 2002);
    }

    /**
     * Everything that is not a clear acceptance. Known refusals (bad parameters, credentials,
     * balance, unknown or blocked user, not allowed, expired token) moved nothing. A duplicate
     * ({@code TRANSACTION_EXIST}), {@code OPERATION_ERROR}, a 5xx or an unreadable answer may hide a
     * transaction that exists: unknown.
     */
    private CallOutcome failure(Result result, ProviderReference ref, String country) {
        return switch (result) {
            case Result.NotSent n -> new CallOutcome.Rejected("PROVIDER_UNAVAILABLE", n.cause());
            case Result.Lost l -> new CallOutcome.Unknown(l.cause(), ref);
            case Result.Answered a when ProviderHttp.isServerError(a.status()) -> new CallOutcome.Unknown("HTTP " + a.status(), ref);
            case Result.Answered a -> {
                Optional<Long> code = Json.wholeAmount(Json.parse(a.body()), "code");
                long c = code.orElse(-1L);
                if (c == 1003 || c == 1002) {
                    forgetToken(country);
                }
                yield switch ((int) c) {
                    case 1002, 1003, 1004, 1005, 2005, 2006, 2007, 2011, 404 ->
                            new CallOutcome.Rejected("CINETPAY_" + Json.text(Json.parse(a.body()), "status").orElse(Long.toString(c)), message(a.body()));
                    default -> new CallOutcome.Unknown("CinetPay code " + c + " (HTTP " + a.status() + ")", ref);
                };
            }
        };
    }

    private static String message(String body) {
        JsonNode json = Json.parse(body);
        return Json.text(json, "description").or(() -> Json.text(json, "message")).orElse("Refused by CinetPay");
    }
}
