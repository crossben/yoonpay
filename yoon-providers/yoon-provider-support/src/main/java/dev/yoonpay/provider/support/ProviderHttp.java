package dev.yoonpay.provider.support;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.Map;

/**
 * The one HTTP client provider adapters use. Its job is to classify failures the way Yoon's
 * money-safety rule needs:
 * <ul>
 *   <li>{@link Result.NotSent} — provably never reached the provider (connection refused, DNS
 *       failure, connect timeout). Safe to fail over.</li>
 *   <li>{@link Result.Lost} — the request may have been processed but no usable answer came back
 *       (read timeout, reset, I/O error mid-exchange). Outcome unknown.</li>
 *   <li>{@link Result.Answered} — an HTTP response. 5xx still means "unknown" for mutating calls:
 *       adapters decide with {@link #isServerError}.</li>
 * </ul>
 * Never retries. Timeouts on every call.
 */
public final class ProviderHttp {

    public sealed interface Result {
        record Answered(int status, String body) implements Result {
        }

        record NotSent(String cause) implements Result {
        }

        record Lost(String cause) implements Result {
        }
    }

    private final HttpClient client;
    private final Duration requestTimeout;

    public ProviderHttp(Duration connectTimeout, Duration requestTimeout) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.requestTimeout = requestTimeout;
    }

    /** Defaults: 5 s to connect, 20 s for the whole exchange (the prior-art integrations used 15–30 s). */
    public static ProviderHttp withDefaults() {
        return new ProviderHttp(Duration.ofSeconds(5), Duration.ofSeconds(20));
    }

    public Result get(URI uri, Map<String, String> headers) {
        return send(base(uri, headers).GET().build());
    }

    public Result postJson(URI uri, Map<String, String> headers, String json) {
        return send(base(uri, headers)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build());
    }

    private HttpRequest.Builder base(URI uri, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("User-Agent", "Yoon");
        headers.forEach(b::header);
        return b;
    }

    private Result send(HttpRequest request) {
        try {
            HttpResponse<String> r = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Result.Answered(r.statusCode(), r.body());
        } catch (HttpConnectTimeoutException e) {
            return new Result.NotSent("connect timeout");
        } catch (HttpTimeoutException e) {
            return new Result.Lost("read timeout");
        } catch (ConnectException e) {
            return new Result.NotSent("connection refused");
        } catch (UnresolvedAddressException e) {
            return new Result.NotSent("unresolved host");
        } catch (IOException e) {
            if (e.getCause() instanceof ConnectException || e.getCause() instanceof UnresolvedAddressException) {
                return new Result.NotSent("connection failed");
            }
            return new Result.Lost(e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result.Lost("interrupted");
        }
    }

    public static boolean isServerError(int status) {
        return status >= 500;
    }

    public static boolean isSuccess(int status) {
        return status >= 200 && status < 300;
    }
}
