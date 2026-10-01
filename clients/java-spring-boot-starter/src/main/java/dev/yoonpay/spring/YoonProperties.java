package dev.yoonpay.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code yoon.*} settings. Typically from the environment: {@code YOON_URL}, {@code YOON_API_KEY},
 * {@code YOON_WEBHOOK_SECRET}, {@code YOON_WEBHOOK_PATHS}.
 */
@ConfigurationProperties("yoon")
public class YoonProperties {

    /** Where Yoon runs, e.g. https://pay.example.com. */
    private String url;

    /** This application's API key (yk_…), from {@code yoon apps create <name>}. */
    private String apiKey;

    /** The secret Yoon signs webhooks with (YOON_APPS_&lt;APP&gt;_WEBHOOK_SECRET on the Yoon side). */
    private String webhookSecret;

    /** Timeout of each call to Yoon (the connect timeout is 5 s). */
    private Duration timeout = Duration.ofSeconds(30);

    private final Webhook webhook = new Webhook();

    public static class Webhook {

        /** Request paths (without the context path) that receive Yoon's events, e.g. /yoon/webhook. */
        private List<String> paths = new ArrayList<>();

        /** How long a handled event id is remembered by the default in-memory store. */
        private Duration dedupe = Duration.ofDays(3);

        public List<String> getPaths() {
            return paths;
        }

        public void setPaths(List<String> paths) {
            this.paths = paths;
        }

        public Duration getDedupe() {
            return dedupe;
        }

        public void setDedupe(Duration dedupe) {
            this.dedupe = dedupe;
        }
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public Webhook getWebhook() {
        return webhook;
    }

    /** Never shows the API key or the webhook secret. */
    @Override
    public String toString() {
        return "YoonProperties[url=" + url + ", webhook.paths=" + webhook.paths + "]";
    }
}
