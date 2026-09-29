package dev.yoonpay.server.api;

import dev.yoonpay.core.lifecycle.PaymentStatus;
import dev.yoonpay.core.provider.InboundWebhook;
import dev.yoonpay.core.provider.ProviderReference;
import dev.yoonpay.server.lifecycle.Alerts;
import dev.yoonpay.server.outbox.OutboxDelivery;
import dev.yoonpay.server.webhook.InboundWebhookProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static dev.yoonpay.server.TestProviders.FAKE_ONE;
import static org.assertj.core.api.Assertions.assertThat;

class WebhookInTest extends ApiTest {

    @Autowired
    InboundWebhookProcessor processor;

    @Autowired
    OutboxDelivery outbox;

    @Autowired
    Alerts alerts;

    private String hookUrl(String app) {
        return "/v1/hooks/fakeone/" + app(app).id();
    }

    private Response deliver(String app, InboundWebhook hook) {
        return postRaw(hookUrl(app), hook.headers(), hook.rawBody());
    }

    private Response pendingPayment() {
        Response r = post("shop", "/v1/payments", payment("wave"));
        assertThat(r.text("status")).isEqualTo("pending");
        return r;
    }

    private String status(String id) {
        return get("shop", "/v1/payments/" + id).text("status");
    }

    private long balance() {
        for (var b : get("shop", "/v1/balances").json().path("data")) {
            if (b.path("account").asString().equals("provider_balance:fakeone")) {
                return b.path("amount").asLong();
            }
        }
        return 0;
    }

    @Test
    void a_genuine_callback_settles_the_payment_after_asking_the_provider() {
        Response p = pendingPayment();
        ProviderReference ref = new ProviderReference(p.text("provider_reference"));
        long before = balance();
        FAKE_ONE.settle(ref, PaymentStatus.SUCCEEDED);

        Response ack = deliver("shop", FAKE_ONE.sendWebhook(ref));
        assertThat(ack.status()).isEqualTo(200);
        assertThat(ack.json().path("received").asBoolean()).isTrue();
        assertThat(status(p.text("id"))).as("stored first, processed by the worker").isEqualTo("pending");

        processor.processPending(10);

        assertThat(status(p.text("id"))).isEqualTo("succeeded");
        assertThat(balance()).isEqualTo(before + 5000);
        var events = get("shop", "/v1/payments/" + p.text("id") + "/events").json();
        assertThat(events.get(events.size() - 1).path("cause").asString()).isEqualTo("webhook");
    }

    @Test
    void a_callback_that_lies_changes_nothing_because_the_provider_is_asked() {
        Response p = pendingPayment();
        ProviderReference ref = new ProviderReference(p.text("provider_reference"));

        // Validly signed, but claims success while the provider still says pending.
        deliver("shop", FAKE_ONE.sendWebhook(ref, "succeeded"));
        processor.processPending(10);

        assertThat(status(p.text("id"))).isEqualTo("pending");
    }

    @Test
    void a_replayed_or_duplicated_callback_is_harmless() {
        Response p = pendingPayment();
        ProviderReference ref = new ProviderReference(p.text("provider_reference"));
        long before = balance();
        FAKE_ONE.settle(ref, PaymentStatus.SUCCEEDED);
        FAKE_ONE.sendDuplicateWebhook(ref);

        for (InboundWebhook hook : FAKE_ONE.drainWebhooks()) {
            deliver("shop", hook);
        }
        processor.processPending(10);
        // A captured callback replayed hours later.
        deliver("shop", FAKE_ONE.sendWebhook(ref));
        processor.processPending(10);

        assertThat(status(p.text("id"))).isEqualTo("succeeded");
        assertThat(balance()).as("credited exactly once").isEqualTo(before + 5000);
        assertThat(get("shop", "/v1/events?resource_id=" + p.text("id")).json().path("data").size())
                .as("one payment.succeeded event").isEqualTo(1);
    }

    @Test
    void a_late_failure_after_success_is_recorded_but_ignored() {
        Response p = pendingPayment();
        ProviderReference ref = new ProviderReference(p.text("provider_reference"));
        FAKE_ONE.settle(ref, PaymentStatus.SUCCEEDED);
        deliver("shop", FAKE_ONE.sendWebhook(ref));
        processor.processPending(10);

        FAKE_ONE.settle(ref, PaymentStatus.FAILED);
        deliver("shop", FAKE_ONE.sendWebhook(ref));
        processor.processPending(10);

        assertThat(status(p.text("id"))).isEqualTo("succeeded");
        var events = get("shop", "/v1/payments/" + p.text("id") + "/events").json();
        var last = events.get(events.size() - 1);
        assertThat(last.path("to_status").asString()).isEqualTo("failed");
        assertThat(last.path("decision").asString()).isEqualTo("ignore");
    }

    @Test
    void a_forged_signature_is_rejected_without_touching_the_payment() {
        Response p = pendingPayment();
        ProviderReference ref = new ProviderReference(p.text("provider_reference"));
        FAKE_ONE.settle(ref, PaymentStatus.SUCCEEDED);

        deliver("shop", FAKE_ONE.forgedWebhook(ref, "succeeded"));
        processor.processPending(10);

        assertThat(status(p.text("id"))).isEqualTo("pending");
        assertThat(jdbc.sql("SELECT result FROM inbound_webhooks ORDER BY id DESC LIMIT 1").query(String.class).single())
                .isEqualTo("invalid_signature");
    }

    @Test
    void a_callback_cannot_reach_another_applications_payment() {
        Response theirs = post("other", "/v1/payments", payment("wave"));
        ProviderReference ref = new ProviderReference(theirs.text("provider_reference"));
        FAKE_ONE.settle(ref, PaymentStatus.SUCCEEDED);

        // Genuinely signed callback about "other"'s payment, sent to "shop"'s URL.
        deliver("shop", FAKE_ONE.sendWebhook(ref));
        processor.processPending(10);

        assertThat(get("other", "/v1/payments/" + theirs.text("id")).text("status")).isEqualTo("pending");
        assertThat(jdbc.sql("SELECT result FROM inbound_webhooks ORDER BY id DESC LIMIT 1").query(String.class).single())
                .isEqualTo("not_found");
    }

    @Test
    void an_amount_mismatch_is_never_settled_and_raises_an_alert() {
        Response p = pendingPayment();
        ProviderReference ref = new ProviderReference(p.text("provider_reference"));
        double before = alerts.count(Alerts.Type.amount_mismatch);
        FAKE_ONE.settle(ref, PaymentStatus.SUCCEEDED, 4000);

        deliver("shop", FAKE_ONE.sendWebhook(ref));
        processor.processPending(10);

        assertThat(status(p.text("id"))).isEqualTo("pending");
        assertThat(alerts.count(Alerts.Type.amount_mismatch)).isEqualTo(before + 1);
    }

    @Test
    void a_partial_payment_stays_pending() {
        Response p = pendingPayment();
        ProviderReference ref = new ProviderReference(p.text("provider_reference"));
        FAKE_ONE.settlePartially(ref, 3000);

        deliver("shop", FAKE_ONE.sendWebhook(ref));
        processor.processPending(10);

        assertThat(status(p.text("id"))).isEqualTo("pending");
    }

    @Test
    void unknown_applications_and_unconfigured_providers_are_404() {
        byte[] body = "{}".getBytes();
        assertThat(postRaw("/v1/hooks/fakeone/00000000-0000-0000-0000-000000000000", Map.of(), body).status()).isEqualTo(404);
        assertThat(postRaw("/v1/hooks/fakeone/not-a-uuid", Map.of(), body).status()).isEqualTo(404);
        assertThat(postRaw("/v1/hooks/faketwo/" + app("other").id(), Map.of(), body).status()).isEqualTo(404);
    }

    @Test
    void oversized_bodies_are_refused() {
        Response r = postRaw(hookUrl("shop"), Map.of("Content-Type", List.of("application/json")), new byte[65 * 1024]);

        assertThat(r.status()).isEqualTo(413);
    }

    @Test
    void settlement_is_announced_to_the_application() {
        Response p = pendingPayment();
        ProviderReference ref = new ProviderReference(p.text("provider_reference"));
        FAKE_ONE.settle(ref, PaymentStatus.SUCCEEDED);
        deliver("shop", FAKE_ONE.sendWebhook(ref));
        processor.processPending(10);

        outbox.deliverDue(50);

        assertThat(RECEIVER.received()).anySatisfy(d -> {
            assertThat(d.header("Yoon-Event-Type")).isEqualTo("payment.succeeded");
            assertThat(d.body()).contains(p.text("id"));
        });
    }
}
