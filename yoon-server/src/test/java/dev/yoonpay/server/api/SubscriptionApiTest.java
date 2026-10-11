package dev.yoonpay.server.api;

import dev.yoonpay.core.billing.BillingPeriods;
import dev.yoonpay.core.billing.Interval;
import dev.yoonpay.core.lifecycle.PaymentStatus;
import dev.yoonpay.core.provider.ProviderReference;
import dev.yoonpay.server.checkout.CheckoutController;
import dev.yoonpay.server.webhook.InboundWebhookProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dev.yoonpay.server.TestProviders.FAKE_ONE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prepaid subscriptions (ADR-0026, ADR-0027) end to end: every period is paid the real way — the
 * customer picks a method on the hosted checkout, the provider confirms, a signed callback arrives,
 * Yoon asks the provider, and only then the invoice and the subscription move.
 */
class SubscriptionApiTest extends BillingTestSupport {

    // ------------------------------------------------------------------ create and first payment

    @Test
    void a_new_subscription_waits_for_its_first_payment_with_an_open_invoice_to_pay() {
        Response r = subscribe(monthly);

        assertThat(r.text("id")).startsWith("sub_");
        assertThat(r.text("status")).isEqualTo("incomplete");
        assertThat(r.json().path("entitled").asBoolean()).isFalse();
        assertThat(r.json().path("current_period_end").isNull()).isTrue();
        assertThat(r.json().path("features").path("seats").asInt()).isEqualTo(5);
        var invoice = r.json().path("latest_invoice");
        assertThat(invoice.path("status").asString()).isEqualTo("open");
        assertThat(invoice.path("kind").asString()).isEqualTo("first");
        assertThat(invoice.path("amount_due").asLong()).isEqualTo(15000);
        assertThat(invoice.path("number").asString()).matches("INV-\\d{4}-\\d{6}");
        assertThat(invoice.path("checkout_url").asString()).startsWith("https://yoon.example/checkout/");
        assertThat(eventTypes(r.text("id"))).containsExactly("subscription.created");
        assertThat(eventTypes(invoice.path("id").asString())).containsExactly("invoice.created");
    }

    @Test
    void the_first_payment_activates_it_for_one_period_from_the_payment() {
        Response r = subscribe(monthly);
        Instant before = Instant.now().minusSeconds(5);

        payInvoice(r.json().path("latest_invoice"));

        Response s = sub(r.text("id"));
        assertThat(s.text("status")).isEqualTo("active");
        assertThat(s.json().path("entitled").asBoolean()).isTrue();
        Instant start = at(s, "current_period_start");
        assertThat(start).isAfter(before);
        assertThat(at(s, "current_period_end")).isEqualTo(BillingPeriods.periodEnd(start, Interval.MONTH, 1,
                BillingPeriods.anchorOf(start)));
        var invoice = s.json().path("latest_invoice");
        assertThat(invoice.path("status").asString()).isEqualTo("paid");
        assertThat(invoice.path("checkout_url").isNull()).isTrue();
        assertThat(invoice.path("period_start").asString()).isEqualTo(s.text("current_period_start"));
        assertThat(eventTypes(r.text("id"))).containsExactly("subscription.created", "subscription.activated");
        assertThat(eventTypes(invoice.path("id").asString())).containsExactly("invoice.created", "invoice.paid");
    }

    @Test
    void a_duplicated_payment_confirmation_adds_one_period_only() {
        Response r = subscribe(monthly);
        payInvoice(r.json().path("latest_invoice"));
        String end = sub(r.text("id")).text("current_period_end");

        // The same callback replayed: the payment is already succeeded, nothing moves again.
        String paymentId = r.json().path("latest_invoice").path("payment").asString();
        ProviderReference ref = new ProviderReference(get("shop", "/v1/payments/" + paymentId).text("provider_reference"));
        var hook = FAKE_ONE.sendWebhook(ref);
        postRaw("/v1/hooks/fakeone/" + app("shop").id(), hook.headers(), hook.rawBody());
        processor.processPending(20);

        assertThat(sub(r.text("id")).text("current_period_end")).isEqualTo(end);
    }

    // ------------------------------------------------------------------ renewals (stacking)

    @Test
    void renewing_early_adds_the_next_period_after_the_current_one() {
        String id = subscribe(monthly).text("id");
        payInvoice(sub(id).json().path("latest_invoice"));
        Instant end1 = at(sub(id), "current_period_end");

        Response invoice = renew(id);
        assertThat(invoice.text("kind")).isEqualTo("renewal");
        payInvoice(invoice.json());

        Response s = sub(id);
        assertThat(s.text("status")).isEqualTo("active");
        assertThat(at(s, "current_period_start")).isEqualTo(end1);
        assertThat(at(s, "current_period_end")).isEqualTo(BillingPeriods.periodEnd(end1, Interval.MONTH, 1,
                BillingPeriods.anchorOf(at(s, "current_period_start"))));
        assertThat(eventTypes(id)).endsWith("subscription.renewed");
    }

    @Test
    void renew_returns_the_same_open_invoice_until_it_is_paid() {
        String id = subscribe(monthly).text("id");
        payInvoice(sub(id).json().path("latest_invoice"));

        String a = renew(id).text("id");
        String b = renew(id).text("id");

        assertThat(b).isEqualTo(a);
        assertThat(jdbc.sql("SELECT count(*) FROM invoices WHERE subscription_id = :s AND status = 'OPEN'")
                .param("s", id).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void paying_in_grace_extends_from_the_old_period_end_so_grace_days_are_not_free() {
        String id = subscribe(monthly).text("id");
        payInvoice(sub(id).json().path("latest_invoice"));
        Instant oldEnd = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        jdbc.sql("UPDATE subscriptions SET status = 'PAST_DUE', current_period_start = :s, current_period_end = :e WHERE id = :id")
                .param("s", java.sql.Timestamp.from(oldEnd.minus(30, ChronoUnit.DAYS)))
                .param("e", java.sql.Timestamp.from(oldEnd)).param("id", id).update();

        payInvoice(renew(id).json());

        Response s = sub(id);
        assertThat(s.text("status")).isEqualTo("active");
        assertThat(at(s, "current_period_start")).isEqualTo(oldEnd);
        assertThat(eventTypes(id)).endsWith("subscription.renewed");
    }

    @Test
    void paying_after_expiry_starts_a_fresh_period_at_the_payment() {
        String id = subscribe(monthly).text("id");
        payInvoice(sub(id).json().path("latest_invoice"));
        jdbc.sql("""
                        UPDATE subscriptions SET status = 'EXPIRED', current_period_start = now() - interval '50 days',
                               current_period_end = now() - interval '20 days' WHERE id = :id""")
                .param("id", id).update();
        Instant before = Instant.now().minusSeconds(5);

        payInvoice(renew(id).json());

        Response s = sub(id);
        assertThat(s.text("status")).isEqualTo("active");
        assertThat(at(s, "current_period_start")).isAfter(before);
        assertThat(eventTypes(id)).endsWith("subscription.activated");
    }

    // ------------------------------------------------------------------ plan changes

    @Test
    void a_downgrade_waits_for_the_period_end_and_the_renewal_bills_the_new_price() {
        String id = subscribe(monthly).text("id");
        payInvoice(sub(id).json().path("latest_invoice"));
        Instant end1 = at(sub(id), "current_period_end");

        Response changed = post("shop", "/v1/subscriptions/" + id + "/change", Map.of("price", yearly));
        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
        assertThat(changed.json().path("price").path("id").asString()).as("unchanged until paid").isEqualTo(monthly);
        assertThat(changed.json().path("pending_change").path("price").path("id").asString()).isEqualTo(yearly);

        Response invoice = renew(id);
        assertThat(invoice.json().path("amount_due").asLong()).isEqualTo(150000);
        payInvoice(invoice.json());

        Response s = sub(id);
        assertThat(s.json().path("price").path("id").asString()).isEqualTo(yearly);
        assertThat(s.json().path("pending_change").isNull()).isTrue();
        assertThat(at(s, "current_period_start")).as("stacked: the paid month is kept").isEqualTo(end1);
        assertThat(eventTypes(id)).contains("subscription.changed");
    }

    @Test
    void an_upgrade_is_billed_now_and_the_unused_time_becomes_extra_days() {
        String id = subscribe(monthly).text("id");
        payInvoice(sub(id).json().path("latest_invoice"));

        Response changed = post("shop", "/v1/subscriptions/" + id + "/change", Map.of("price", premium));
        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
        var invoice = changed.json().path("latest_invoice");
        assertThat(invoice.path("kind").asString()).isEqualTo("upgrade");
        assertThat(invoice.path("amount_due").asLong()).isEqualTo(30000);
        assertThat(changed.json().path("price").path("id").asString()).as("nothing changes before payment").isEqualTo(monthly);

        Instant before = Instant.now().minusSeconds(5);
        payInvoice(invoice);

        Response s = sub(id);
        assertThat(s.json().path("price").path("id").asString()).isEqualTo(premium);
        Instant start = at(s, "current_period_start");
        assertThat(start).isAfter(before);
        Instant plainEnd = BillingPeriods.periodEnd(start, Interval.MONTH, 1, BillingPeriods.anchorOf(start));
        // About a month of 15 000 left, worth about half a month of 30 000: 14 or 15 whole days.
        long extra = Duration.between(plainEnd, at(s, "current_period_end")).toDays();
        assertThat(extra).isBetween(13L, 15L);
    }

    @Test
    void only_an_upgrade_can_apply_now_and_the_currency_never_changes() {
        String id = subscribe(monthly).text("id");
        payInvoice(sub(id).json().path("latest_invoice"));

        Response downNow = post("shop", "/v1/subscriptions/" + id + "/change", Map.of("price", yearly, "when", "now"));
        Response euros = post("shop", "/v1/subscriptions/" + id + "/change", Map.of("price", euro));

        assertThat(downNow.status()).isEqualTo(422);
        assertThat(downNow.text("code")).isEqualTo("change_not_upgrade");
        assertThat(euros.status()).isEqualTo(422);
        assertThat(euros.text("code")).isEqualTo("currency_mismatch");
    }

    // ------------------------------------------------------------------ cancel and resume

    @Test
    void cancelling_at_period_end_is_reversible() {
        String id = subscribe(monthly).text("id");
        payInvoice(sub(id).json().path("latest_invoice"));

        Response c = post("shop", "/v1/subscriptions/" + id + "/cancel", Map.of());
        assertThat(c.json().path("cancel_at_period_end").asBoolean()).isTrue();
        assertThat(c.text("status")).as("still entitled until the period ends").isEqualTo("active");

        Response r = post("shop", "/v1/subscriptions/" + id + "/resume", null);
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(r.json().path("cancel_at_period_end").asBoolean()).isFalse();
    }

    @Test
    void cancelling_now_ends_it_and_voids_the_open_invoice_but_a_late_payment_still_counts() {
        String id = subscribe(monthly).text("id");
        payInvoice(sub(id).json().path("latest_invoice"));
        Response open = renew(id);

        Response c = post("shop", "/v1/subscriptions/" + id + "/cancel", Map.of("at_period_end", false));
        assertThat(c.text("status")).isEqualTo("canceled");
        assertThat(c.json().path("entitled").asBoolean()).isFalse();
        assertThat(get("shop", "/v1/invoices/" + open.text("id")).text("status")).isEqualTo("void");

        // The customer had the checkout open and paid anyway: money received is never ignored.
        double alerts = meters.counter("yoon.alerts", "type", "late_invoice_payment").count();
        payInvoice(open.json());

        assertThat(sub(id).text("status")).isEqualTo("active");
        assertThat(get("shop", "/v1/invoices/" + open.text("id")).text("status")).isEqualTo("paid");
        assertThat(meters.counter("yoon.alerts", "type", "late_invoice_payment").count()).isEqualTo(alerts + 1);
    }

    // ------------------------------------------------------------------ invoices and isolation

    @Test
    void invoice_numbers_follow_each_other_without_gaps() {
        String a = subscribe(monthly).json().path("latest_invoice").path("number").asString();
        String b = subscribe(monthly).json().path("latest_invoice").path("number").asString();

        long na = Long.parseLong(a.substring(a.lastIndexOf('-') + 1));
        long nb = Long.parseLong(b.substring(b.lastIndexOf('-') + 1));
        assertThat(nb).isEqualTo(na + 1);
    }

    @Test
    void invoices_are_listed_per_subscription() {
        String id = subscribe(monthly).text("id");
        payInvoice(sub(id).json().path("latest_invoice"));
        renew(id);

        Response list = get("shop", "/v1/invoices?subscription=" + id);
        assertThat(list.json().path("data").size()).isEqualTo(2);
        assertThat(list.json().path("data").get(0).path("status").asString()).isEqualTo("open");
    }

    @Test
    void subscriptions_and_invoices_are_private_to_their_application() {
        Response r = subscribe(monthly);

        assertThat(get("other", "/v1/subscriptions/" + r.text("id")).status()).isEqualTo(404);
        assertThat(post("other", "/v1/subscriptions/" + r.text("id") + "/renew", Map.of()).status()).isEqualTo(404);
        assertThat(get("other", "/v1/invoices/" + r.json().path("latest_invoice").path("id").asString()).status()).isEqualTo(404);
        assertThat(post("other", "/v1/subscriptions", Map.of("customer", customer, "price", monthly)).status())
                .as("another application's customer").isEqualTo(422);
    }

    // ------------------------------------------------------------------ money safety

    @Test
    void concurrent_renewals_create_one_open_invoice_with_one_payment() throws Exception {
        String id = subscribe(monthly).text("id");
        payInvoice(sub(id).json().path("latest_invoice"));

        var pool = java.util.concurrent.Executors.newFixedThreadPool(6);
        var latch = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<Response>> calls = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            calls.add(pool.submit(() -> {
                latch.await();
                return post("shop", "/v1/subscriptions/" + id + "/renew", Map.of());
            }));
        }
        latch.countDown();
        java.util.Set<String> invoiceIds = new java.util.HashSet<>();
        java.util.Set<String> paymentIds = new java.util.HashSet<>();
        for (var c : calls) {
            Response r = c.get();
            assertThat(r.status()).as(r.body()).isEqualTo(200);
            invoiceIds.add(r.text("id"));
            paymentIds.add(r.text("payment"));
        }
        pool.shutdown();

        assertThat(invoiceIds).hasSize(1);
        assertThat(paymentIds).hasSize(1);
    }

    @Test
    void a_payment_confirmed_for_another_amount_pays_nothing() {
        Response r = subscribe(monthly);
        var invoice = r.json().path("latest_invoice");
        String paymentId = invoice.path("payment").asString();
        String token = URI.create(invoice.path("checkout_url").asString()).getQuery().substring(2);
        send(null, "POST", "/checkout/api/" + paymentId + "/attempts", Map.of("method", "wave"), null,
                Map.of(CheckoutController.TOKEN_HEADER, token));
        ProviderReference ref = new ProviderReference(get("shop", "/v1/payments/" + paymentId).text("provider_reference"));
        FAKE_ONE.settle(ref, PaymentStatus.SUCCEEDED, 100);   // the customer paid 100, not 15 000
        var hook = FAKE_ONE.sendWebhook(ref);
        postRaw("/v1/hooks/fakeone/" + app("shop").id(), hook.headers(), hook.rawBody());
        processor.processPending(20);

        assertThat(get("shop", "/v1/invoices/" + invoice.path("id").asString()).text("status")).isEqualTo("open");
        assertThat(sub(r.text("id")).text("status")).isEqualTo("incomplete");
    }
}
