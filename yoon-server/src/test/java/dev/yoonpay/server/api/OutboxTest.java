package dev.yoonpay.server.api;

import dev.yoonpay.server.TestWebhookReceiver;
import dev.yoonpay.server.outbox.OutboxDelivery;
import dev.yoonpay.server.outbox.WebhookSignature;
import dev.yoonpay.testkit.Behaviour;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static dev.yoonpay.server.TestProviders.FAKE_ONE;
import static dev.yoonpay.server.TestProviders.FAKE_TWO;
import static org.assertj.core.api.Assertions.assertThat;

class OutboxTest extends ApiTest {

    @Autowired
    OutboxDelivery delivery;

    @BeforeEach
    void drainEarlierEvents() {
        // Events left by other test classes must not interfere with counts here.
        jdbc.sql("UPDATE outbound_events SET delivery_status = 'DELIVERED' WHERE delivery_status = 'PENDING'").update();
    }

    /** A payment that fails at creation: the simplest way to produce a payment.failed event. */
    private String failedPayment(String app) {
        FAKE_ONE.script(Behaviour.reject("DECLINED"));
        FAKE_TWO.script(Behaviour.reject("DECLINED"));
        return post(app, "/v1/payments", payment("wave")).text("id");
    }

    private String eventFor(String app, String resourceId) {
        return get(app, "/v1/events?resource_id=" + resourceId).json().path("data").get(0).path("id").asString();
    }

    @Test
    void events_are_delivered_signed_and_verifiable() {
        String paymentId = failedPayment("shop");

        delivery.deliverDue(50);

        var d = RECEIVER.received().getFirst();
        assertThat(d.header("Yoon-Event-Type")).isEqualTo("payment.failed");
        assertThat(d.header("Yoon-Event-Id")).startsWith("evt_");
        assertThat(d.header("Content-Type")).isEqualTo("application/json");
        assertThat(WebhookSignature.verify(TestWebhookReceiver.SECRET, d.header("Yoon-Signature"), d.body(),
                Instant.now().getEpochSecond())).isTrue();
        assertThat(WebhookSignature.verify("wrong-secret-wrong-secret-wrong-secret", d.header("Yoon-Signature"), d.body(),
                Instant.now().getEpochSecond())).isFalse();
        assertThat(WebhookSignature.verify(TestWebhookReceiver.SECRET, d.header("Yoon-Signature"), d.body(),
                Instant.now().getEpochSecond() + 600)).as("stale timestamps are rejected").isFalse();
        assertThat(d.body()).contains("\"type\":\"payment.failed\"").contains(paymentId);

        Response event = get("shop", "/v1/events/" + d.header("Yoon-Event-Id"));
        assertThat(event.json().path("delivery").path("status").asString()).isEqualTo("delivered");
        assertThat(event.json().path("payload").path("data").path("object").path("id").asString()).isEqualTo(paymentId);
    }

    @Test
    void a_failing_endpoint_is_retried_later_not_immediately() {
        String paymentId = failedPayment("shop");
        RECEIVER.respondWith(500);

        delivery.deliverDue(50);
        delivery.deliverDue(50);

        assertThat(RECEIVER.received()).hasSize(1);
        Response event = get("shop", "/v1/events/" + eventFor("shop", paymentId));
        assertThat(event.json().path("delivery").path("status").asString()).isEqualTo("pending");
        assertThat(event.json().path("delivery").path("attempts").asInt()).isEqualTo(1);
        assertThat(event.json().path("delivery").path("last_status_code").asInt()).isEqualTo(500);

        RECEIVER.respondWith(200);
        jdbc.sql("UPDATE outbound_events SET next_attempt_at = now() WHERE resource_id = :id").param("id", paymentId).update();
        delivery.deliverDue(50);

        assertThat(RECEIVER.received()).hasSize(2);
        assertThat(get("shop", "/v1/events/" + eventFor("shop", paymentId)).json().path("delivery").path("status").asString())
                .isEqualTo("delivered");
    }

    @Test
    void after_the_last_attempt_an_event_is_dead_and_the_operator_can_replay_it() {
        String paymentId = failedPayment("shop");
        String eventId = eventFor("shop", paymentId);
        RECEIVER.respondWith(503);
        for (int i = 0; i < OutboxDelivery.MAX_ATTEMPTS; i++) {
            jdbc.sql("UPDATE outbound_events SET next_attempt_at = now() WHERE id = :id").param("id", eventId).update();
            delivery.deliverDue(50);
        }

        assertThat(get("shop", "/v1/events/" + eventId).json().path("delivery").path("status").asString()).isEqualTo("dead");
        assertThat(admin("GET", "/admin/v1/dead-letters", null).json().path("data"))
                .extracting(e -> e.path("id").asString()).contains(eventId);

        RECEIVER.respondWith(200);
        Response replay = admin("POST", "/admin/v1/dead-letters/" + eventId + "/replay", null);
        assertThat(replay.status()).isEqualTo(202);
        delivery.deliverDue(50);

        assertThat(get("shop", "/v1/events/" + eventId).json().path("delivery").path("status").asString()).isEqualTo("delivered");
        assertThat(admin("POST", "/admin/v1/dead-letters/" + eventId + "/replay", null).text("code")).isEqualTo("not_dead");
    }

    @Test
    void concurrent_workers_never_deliver_the_same_event_twice() throws Exception {
        for (int i = 0; i < 20; i++) {
            failedPayment("shop");
        }
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> workers = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(4)) {
            for (int i = 0; i < 4; i++) {
                workers.add(pool.submit(() -> {
                    go.await();
                    int n = 0;
                    int got;
                    while ((got = delivery.deliverDue(3)) > 0) {
                        n += got;
                    }
                    return n;
                }));
            }
            go.countDown();
            int total = 0;
            for (Future<Integer> w : workers) {
                total += w.get();
            }
            assertThat(total).isEqualTo(20);
        }
        List<String> ids = RECEIVER.received().stream().map(d -> d.header("Yoon-Event-Id")).toList();
        assertThat(ids).hasSize(20).doesNotHaveDuplicates();
    }

    @Test
    void an_application_without_a_webhook_url_can_still_read_its_events() {
        String paymentId = failedPayment("other");

        Response list = get("other", "/v1/events?type=payment.failed&resource_id=" + paymentId);
        var event = list.json().path("data").get(0);
        assertThat(event.path("delivery").path("status").asString()).isEqualTo("no_endpoint");

        Response redeliver = post("other", "/v1/events/" + event.path("id").asString() + "/redeliver", null);
        assertThat(redeliver.status()).isEqualTo(422);
        assertThat(redeliver.text("code")).isEqualTo("no_webhook_endpoint");
    }

    @Test
    void an_application_can_ask_for_an_event_again() {
        String paymentId = failedPayment("shop");
        delivery.deliverDue(50);
        String eventId = eventFor("shop", paymentId);

        Response r = post("shop", "/v1/events/" + eventId + "/redeliver", null);
        delivery.deliverDue(50);

        assertThat(r.status()).isEqualTo(202);
        assertThat(RECEIVER.received()).filteredOn(d -> d.header("Yoon-Event-Id").equals(eventId)).hasSize(2);
        assertThat(get("other", "/v1/events/" + eventId).status()).isEqualTo(404);
    }

    @Test
    void the_admin_api_requires_the_operator_token() {
        assertThat(send(null, "GET", "/admin/v1/dead-letters", null, null).status()).isEqualTo(401);
        assertThat(send("shop", "GET", "/admin/v1/dead-letters", null, null).status()).isEqualTo(401);
        assertThat(admin("GET", "/admin/v1/dead-letters", null).status()).isEqualTo(200);
    }

    @Test
    void backoff_doubles_and_is_capped() {
        assertThat(OutboxDelivery.backoff(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(OutboxDelivery.backoff(2)).isEqualTo(Duration.ofSeconds(60));
        assertThat(OutboxDelivery.backoff(20)).isEqualTo(Duration.ofHours(6));
    }
}
