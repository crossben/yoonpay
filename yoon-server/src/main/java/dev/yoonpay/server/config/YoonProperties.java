package dev.yoonpay.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
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
        sourceUrl = sourceUrl == null ? URI.create("https://github.com/yoonpay/yoon") : sourceUrl;
        publicUrl = publicUrl == null || publicUrl.toString().isBlank() ? null : publicUrl;
    }

    public record App(Map<String, Provider> providers) {
        public App {
            providers = providers == null ? Map.of() : Map.copyOf(providers);
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
        return apps.getOrDefault(name, new App(Map.of()));
    }
}
