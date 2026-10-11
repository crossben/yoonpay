package dev.yoonpay.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Map;

/**
 * Yoon's own configuration, from environment variables. Provider credentials per application:
 * {@code YOON_APPS_<APP>_PROVIDERS_<PROVIDER>_CREDENTIALS_<KEY>} and
 * {@code YOON_APPS_<APP>_PROVIDERS_<PROVIDER>_PRIORITY}. Credentials are never logged.
 *
 * @param sourceUrl where users of this instance can get its source code (AGPL §13). A
 *                  modified deployment must point this at its own source.
 * @param publicUrl the address providers reach this instance at (HTTPS in production); used to
 *                  build webhook callback URLs. Null: providers are not given a callback URL.
 */
@ConfigurationProperties("yoon")
public record YoonProperties(Map<String, App> apps, URI sourceUrl, URI publicUrl) {

    public YoonProperties {
        apps = apps == null ? Map.of() : Map.copyOf(apps);
        sourceUrl = sourceUrl == null ? URI.create("https://github.com/crossben/yoonpay") : sourceUrl;
        publicUrl = publicUrl == null || publicUrl.toString().isBlank() ? null : publicUrl;
    }

    /**
     * @param webhook where Yoon sends this application's events; null: events are only listed via the API
     * @param billing subscription settings ({@code YOON_APPS_<APP>_BILLING_*}, ADR-0026)
     */
    public record App(Map<String, Provider> providers, Webhook webhook, Billing billing) {
        public App {
            providers = providers == null ? Map.of() : Map.copyOf(providers);
            billing = billing == null ? Billing.DEFAULTS : billing;
        }
    }

    /**
     * Billing settings per application (ADR-0026, ADR-0027).
     *
     * @param graceDays     days a subscription stays entitled (past due) after its period ends unpaid
     * @param reminderDays  days before the period end that {@code subscription.renewal_upcoming} is sent
     * @param timezone      the application's time zone, for "days before" (default Africa/Dakar)
     * @param invoicePrefix invoice numbers are {@code <prefix>-<year>-<000001>}
     * @param invoiceTtl    how long an unpaid first invoice keeps an incomplete subscription alive
     */
    public record Billing(Integer graceDays, Integer reminderDays, ZoneId timezone, String invoicePrefix,
                          Duration invoiceTtl) {
        public static final Billing DEFAULTS = new Billing(null, null, null, null, null);

        public Billing {
            graceDays = graceDays == null ? 3 : graceDays;
            reminderDays = reminderDays == null ? 3 : reminderDays;
            timezone = timezone == null ? ZoneId.of("Africa/Dakar") : timezone;
            invoicePrefix = invoicePrefix == null || invoicePrefix.isBlank() ? "INV" : invoicePrefix;
            invoiceTtl = invoiceTtl == null ? Duration.ofDays(7) : invoiceTtl;
            if (graceDays < 0 || graceDays > 90) {
                throw new IllegalArgumentException("billing grace days must be 0–90");
            }
            if (reminderDays < 0 || reminderDays > 60) {
                throw new IllegalArgumentException("billing reminder days must be 0–60");
            }
            if (!invoicePrefix.matches("[A-Z0-9]{1,12}")) {
                throw new IllegalArgumentException("billing invoice prefix must be 1–12 capital letters or digits");
            }
            if (invoiceTtl.isNegative() || invoiceTtl.isZero()) {
                throw new IllegalArgumentException("billing invoice TTL must be positive");
            }
        }
    }

    /**
     * {@code YOON_APPS_<APP>_WEBHOOK_URL} and {@code YOON_APPS_<APP>_WEBHOOK_SECRET} (at least 32
     * characters; the application verifies Yoon's signatures with it).
     */
    public record Webhook(URI url, String secret) {
        public Webhook {
            if (url != null && (secret == null || secret.length() < 32)) {
                throw new IllegalArgumentException("A webhook secret of at least 32 characters is required with a webhook URL");
            }
        }

        @Override
        public String toString() {
            return "Webhook[url=" + url + ", secret=(hidden)]";
        }
    }

    /** @param priority lower is preferred; defaults to 100 */
    public record Provider(Integer priority, Map<String, String> credentials) {
        public Provider {
            priority = priority == null ? 100 : priority;
            credentials = credentials == null ? Map.of() : Map.copyOf(credentials);
        }

        @Override
        public String toString() {
            return "Provider[priority=" + priority + ", credentials=" + credentials.keySet() + " (values hidden)]";
        }
    }

    public App app(String name) {
        return apps.getOrDefault(name, new App(Map.of(), null, null));
    }
}
