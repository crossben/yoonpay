package dev.yoonpay.server.api;

import dev.yoonpay.testkit.Behaviour;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static dev.yoonpay.server.TestProviders.FAKE_ONE;
import static org.assertj.core.api.Assertions.assertThat;

/** The metrics the dashboard and alert rules in deploy/ rely on exist under these names. */
class MetricsTest extends ApiTest {

    @Test
    void business_metrics_are_exposed_for_prometheus() {
        post("shop", "/v1/payments", payment("wave"));
        FAKE_ONE.script(Behaviour.hang(Duration.ofMillis(5)));
        post("shop", "/v1/payments", payment("wave"));

        String scrape = send(null, "GET", "/actuator/prometheus", null, null).body();

        assertThat(scrape)
                .contains("yoon_provider_calls_seconds_bucket{")
                .containsPattern("yoon_provider_calls_seconds_count\\{[^}]*outcome=\"accepted\"[^}]*provider=\"fakeone\"")
                .containsPattern("yoon_provider_calls_seconds_count\\{[^}]*outcome=\"unknown\"")
                .containsPattern("yoon_status_changes_total\\{[^}]*provider=\"fakeone\"[^}]*resource=\"payment\"[^}]*status=\"pending\"")
                .contains("yoon_outbox_pending ")
                .contains("yoon_outbox_dead ")
                .contains("yoon_payments_open ")
                .contains("yoon_payouts_needs_review ");
    }
}
