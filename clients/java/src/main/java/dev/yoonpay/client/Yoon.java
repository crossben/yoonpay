package dev.yoonpay.client;

import dev.yoonpay.client.generated.ApiClient;
import dev.yoonpay.client.generated.ApiException;
import dev.yoonpay.client.generated.api.EventsApi;
import dev.yoonpay.client.generated.api.LedgerApi;
import dev.yoonpay.client.generated.api.MetaApi;
import dev.yoonpay.client.generated.api.PaymentsApi;
import dev.yoonpay.client.generated.api.PayoutsApi;
import dev.yoonpay.client.generated.api.RefundsApi;
import dev.yoonpay.client.generated.model.CreatePaymentRequest;
import dev.yoonpay.client.generated.model.CreatePayoutRequest;
import dev.yoonpay.client.generated.model.CreateRefundRequest;
import dev.yoonpay.client.generated.model.Payment;
import dev.yoonpay.client.generated.model.Payout;
import dev.yoonpay.client.generated.model.Refund;

import java.net.URI;
import java.time.Duration;

/**
 * Entry point. Common calls have helpers; every operation of the API contract is available on
 * the generated API objects ({@link #payments()}, {@link #refunds()}, …), wrapped with
 * {@link #call(Call)} to get {@link YoonException}s. CSV exports use {@link #exportCsv}: the
 * generated exports API cannot read CSV.
 *
 * <p>Every create call takes an idempotency key: use something tied to your intent (your order
 * id) so that a retry after a timeout returns the original result instead of charging twice.
 */
public final class Yoon {

    @FunctionalInterface
    public interface Call<T> {
        T run() throws ApiException;
    }

    private final ApiClient client;
    private final URI base;
    private final String apiKey;
    private final Duration timeout;
    private final java.net.http.HttpClient http;

    /**
     * @param baseUrl e.g. {@code https://pay.example.com}
     * @param apiKey  the application key ({@code yk_…})
     */
    public Yoon(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, Duration.ofSeconds(30));
    }

    public Yoon(String baseUrl, String apiKey, Duration timeout) {
        URI uri = URI.create(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        this.base = uri;
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.client = new ApiClient()
                .setScheme(uri.getScheme())
                .setHost(uri.getHost())
                .setPort(uri.getPort() == -1 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort())
                .setBasePath(uri.getPath() == null ? "" : uri.getPath())
                .setReadTimeout(timeout)
                .setConnectTimeout(Duration.ofSeconds(5))
                .setRequestInterceptor(r -> r.header("Authorization", "Bearer " + apiKey).header("User-Agent", "yoon-java"));
    }

    public Payment createPayment(CreatePaymentRequest payment, String idempotencyKey) {
        return call(() -> payments().createPayment(idempotencyKey, payment));
    }

    public Payment getPayment(String id) {
        return call(() -> payments().getPayment(id));
    }

    /** @param amount null refunds whatever is left */
    public Refund refund(String paymentId, String idempotencyKey, Long amount, String reason) {
        return call(() -> refunds().createRefund(paymentId, idempotencyKey, new CreateRefundRequest().amount(amount).reason(reason)));
    }

    public Payout createPayout(CreatePayoutRequest payout, String idempotencyKey) {
        return call(() -> payouts().createPayout(idempotencyKey, payout));
    }

    /** Runs any generated API call, turning Yoon's problem+json errors into {@link YoonException}. */
    public <T> T call(Call<T> call) {
        try {
            return call.run();
        } catch (ApiException e) {
            throw YoonException.from(e);
        }
    }

    public PaymentsApi payments() {
        return new PaymentsApi(client);
    }

    public RefundsApi refunds() {
        return new RefundsApi(client);
    }

    public PayoutsApi payouts() {
        return new PayoutsApi(client);
    }

    public EventsApi events() {
        return new EventsApi(client);
    }

    public LedgerApi ledger() {
        return new LedgerApi(client);
    }

    /** CSV exports. */
    public enum Export { PAYMENTS, REFUNDS, PAYOUTS, LEDGER }

    /**
     * Downloads a CSV export (amounts in minor units, UTC timestamps, phones masked).
     *
     * @param from inclusive, or null
     * @param to   exclusive, or null
     */
    public String exportCsv(Export export, java.time.OffsetDateTime from, java.time.OffsetDateTime to) {
        StringBuilder query = new StringBuilder();
        if (from != null) {
            query.append("from=").append(java.net.URLEncoder.encode(from.toString(), java.nio.charset.StandardCharsets.UTF_8));
        }
        if (to != null) {
            query.append(query.isEmpty() ? "" : "&").append("to=")
                    .append(java.net.URLEncoder.encode(to.toString(), java.nio.charset.StandardCharsets.UTF_8));
        }
        URI uri = URI.create(base + "/v1/exports/" + export.name().toLowerCase() + ".csv" + (query.isEmpty() ? "" : "?" + query));
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Authorization", "Bearer " + apiKey).header("User-Agent", "yoon-java").GET().build();
        try {
            java.net.http.HttpResponse<String> r = http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                throw YoonException.http(r.statusCode(), r.body());
            }
            return r.body();
        } catch (java.io.IOException e) {
            throw YoonException.transport("Yoon unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw YoonException.transport("interrupted", e);
        }
    }

    public MetaApi meta() {
        return new MetaApi(client);
    }
}
