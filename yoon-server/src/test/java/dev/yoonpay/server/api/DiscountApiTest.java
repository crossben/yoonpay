package dev.yoonpay.server.api;

import dev.yoonpay.server.checkout.CheckoutController;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** Coupons on subscriptions and invoices (ADR-0029). */
class DiscountApiTest extends BillingTestSupport {

    private String coupon(Map<String, Object> terms) {
        Map<String, Object> body = new HashMap<>(terms);
        body.put("code", "C" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        Response r = post("shop", "/v1/coupons", body);
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return r.text("code");
    }

    private Response subscribeWith(String price, String code) {
        return post("shop", "/v1/subscriptions", Map.of("customer", customer, "price", price, "coupon", code,
                "return_url", "https://shop.example/billing"));
    }

    private long ledgerPostings() {
        return jdbc.sql("SELECT count(*) FROM ledger_postings").query(Long.class).single();
    }

    @Test
    void a_repeating_percent_coupon_discounts_two_paid_invoices_then_stops() {
        String code = coupon(Map.of("percent_off", 33, "duration", "repeating", "duration_in_periods", 2));
        Response created = subscribeWith(monthly, code);
        String id = created.text("id");
        JsonNode first = created.json().path("latest_invoice");

        assertThat(first.path("subtotal").asLong()).isEqualTo(15000);
        assertThat(first.path("discount_amount").asLong()).isEqualTo(4950);
        assertThat(first.path("amount_due").asLong()).isEqualTo(10050);
        assertThat(first.path("discount").path("code").asString()).isEqualTo(code);
        assertThat(created.json().path("discount").path("periods_remaining").asInt()).as("not consumed before payment").isEqualTo(2);
        String token = URI.create(first.path("checkout_url").asString()).getQuery().substring(2);
        JsonNode checkout = send(null, "GET", "/checkout/api/" + first.path("payment").asString(), null, null,
                Map.of(CheckoutController.TOKEN_HEADER, token)).json();
        assertThat(checkout.path("amount").asLong()).as("the customer pays the discounted amount").isEqualTo(10050);
        assertThat(checkout.path("billing").path("coupon_code").asString()).isEqualTo(code);
        assertThat(checkout.path("billing").path("subtotal").asLong()).isEqualTo(15000);

        payInvoice(first);
        assertThat(sub(id).json().path("discount").path("periods_remaining").asInt()).isEqualTo(1);

        Response second = renew(id);
        assertThat(second.json().path("amount_due").asLong()).isEqualTo(10050);
        payInvoice(second.json());
        assertThat(sub(id).json().path("discount").isNull()).as("both periods used").isTrue();

        assertThat(renew(id).json().path("amount_due").asLong()).isEqualTo(15000);
    }

    @Test
    void nothing_to_pay_means_paid_at_creation_without_a_payment_or_a_ledger_posting() {
        String code = coupon(Map.of("amount_off", Map.of("currency", "XOF", "amount", 20000), "duration", "once"));
        long postings = ledgerPostings();

        Response created = subscribeWith(monthly, code);

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(created.text("status")).isEqualTo("active");
        JsonNode invoice = created.json().path("latest_invoice");
        assertThat(invoice.path("amount_due").asLong()).isZero();
        assertThat(invoice.path("status").asString()).isEqualTo("paid");
        assertThat(invoice.path("payment").isNull()).isTrue();
        assertThat(invoice.path("checkout_url").isNull()).isTrue();
        assertThat(ledgerPostings()).isEqualTo(postings);
        assertThat(eventTypes(created.text("id"))).contains("subscription.activated");
        assertThat(eventTypes(invoice.path("id").asString())).contains("invoice.paid");

        String receiptUrl = invoice.path("receipt_url").asString();
        Response receipt = send(null, "GET", "/checkout/api/receipts/" + invoice.path("id").asString(), null, null,
                Map.of(CheckoutController.TOKEN_HEADER, URI.create(receiptUrl).getQuery().substring(2)));
        assertThat(receipt.json().path("discount_amount").asLong()).isEqualTo(15000);
        assertThat(receipt.text("coupon_code")).isEqualTo(code);
    }

    @Test
    void a_forever_free_coupon_pays_every_renewal_by_itself() {
        String id = subscribeWith(monthly, coupon(Map.of("percent_off", 100, "duration", "forever"))).text("id");

        Response renewal = renew(id);

        assertThat(renewal.text("status")).isEqualTo("paid");
        assertThat(sub(id).json().path("discount").path("periods_remaining").isNull()).isTrue();
    }

    @Test
    void every_refusal_says_why() {
        String archived = coupon(Map.of("percent_off", 10, "duration", "once"));
        String id = get("shop", "/v1/coupons?code=" + archived).json().path("data").get(0).path("id").asString();
        send("shop", "PATCH", "/v1/coupons/" + id, Map.of("active", false), "k-" + UUID.randomUUID());
        String expired = coupon(Map.of("percent_off", 10, "duration", "once", "redeem_by", "2020-01-01T00:00:00Z"));
        String single = coupon(Map.of("percent_off", 10, "duration", "once", "max_redemptions", 1));
        String yearlyPlan = get("shop", "/v1/prices/" + premium).text("plan");
        String otherPlan = coupon(Map.of("percent_off", 10, "duration", "once", "plans", List.of(yearlyPlan)));
        String euros = coupon(Map.of("amount_off", Map.of("currency", "EUR", "amount", 100), "duration", "once"));
        assertThat(subscribeWith(monthly, single).status()).isEqualTo(201);

        Map<String, String> reasons = new HashMap<>();
        for (var e : Map.of("unknown", "NOPE" + UUID.randomUUID().toString().substring(0, 6), "archived", archived,
                "expired", expired, "exhausted", single, "plan", otherPlan, "currency", euros).entrySet()) {
            Response r = subscribeWith(monthly, e.getValue());
            assertThat(r.status()).as(e.getKey() + ": " + r.body()).isEqualTo(422);
            assertThat(r.text("code")).isEqualTo("coupon_not_applicable");
            reasons.put(e.getKey(), r.text("reason"));
        }
        reasons.forEach((expected, actual) -> assertThat(actual).isEqualTo(expected));
        assertThat(jdbc.sql("SELECT count(*) FROM subscriptions WHERE customer_id = :c").param("c", customer)
                .query(Long.class).single()).as("refused creations leave nothing behind").isEqualTo(1);
    }

    @Test
    void codes_are_redeemed_whatever_the_case() {
        String code = coupon(Map.of("percent_off", 10, "duration", "once"));

        assertThat(subscribeWith(monthly, code.toLowerCase()).json().path("latest_invoice").path("discount_amount").asLong())
                .isEqualTo(1500);
    }

    @Test
    void the_last_use_of_a_coupon_goes_to_one_subscription_only() throws Exception {
        String code = coupon(Map.of("percent_off", 10, "duration", "once", "max_redemptions", 1));
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> attempts = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(4)) {
            for (int i = 0; i < 4; i++) {
                attempts.add(pool.submit(() -> {
                    go.await();
                    return subscribeWith(monthly, code).status();
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : attempts) {
                statuses.add(f.get());
            }
            assertThat(statuses).containsOnlyOnce(201).containsOnly(201, 422);
        }
        assertThat(get("shop", "/v1/coupons?code=" + code).json().path("data").get(0).path("times_redeemed").asInt()).isEqualTo(1);
    }

    @Test
    void a_coupon_applied_later_replaces_an_unpaid_invoice_and_can_be_removed() {
        String id = subscribe(monthly).text("id");
        String unpaid = sub(id).json().path("latest_invoice").path("id").asString();
        String code = coupon(Map.of("percent_off", 20, "duration", "forever"));

        Response applied = post("shop", "/v1/subscriptions/" + id + "/discount", Map.of("coupon", code));
        assertThat(applied.status()).as(applied.body()).isEqualTo(200);
        assertThat(get("shop", "/v1/invoices/" + unpaid).text("status")).isEqualTo("void");
        assertThat(renew(id).json().path("amount_due").asLong()).isEqualTo(12000);

        Response removed = send("shop", "DELETE", "/v1/subscriptions/" + id + "/discount", null, "k-" + UUID.randomUUID());
        assertThat(removed.status()).as(removed.body()).isEqualTo(200);
        assertThat(removed.json().path("discount").isNull()).isTrue();
        assertThat(renew(id).json().path("amount_due").asLong()).isEqualTo(15000);
        assertThat(eventTypes(id)).contains("subscription.changed");
    }

    @Test
    void the_discount_does_not_change_under_a_payment_in_progress() {
        String id = subscribe(monthly).text("id");
        JsonNode invoice = sub(id).json().path("latest_invoice");
        String token = URI.create(invoice.path("checkout_url").asString()).getQuery().substring(2);
        send(null, "POST", "/checkout/api/" + invoice.path("payment").asString() + "/attempts", Map.of("method", "wave"),
                null, Map.of(CheckoutController.TOKEN_HEADER, token));

        Response r = post("shop", "/v1/subscriptions/" + id + "/discount",
                Map.of("coupon", coupon(Map.of("percent_off", 20, "duration", "once"))));

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.text("code")).isEqualTo("payment_in_progress");
        assertThat(get("shop", "/v1/invoices/" + invoice.path("id").asString()).text("status")).isEqualTo("open");
    }

    @Test
    void a_voided_discounted_invoice_does_not_use_up_the_coupon() {
        String id = subscribeWith(monthly, coupon(Map.of("percent_off", 50, "duration", "once"))).text("id");

        post("shop", "/v1/subscriptions/" + id + "/cancel", Map.of("at_period_end", false));

        assertThat(sub(id).json().path("discount").path("periods_remaining").asInt()).isEqualTo(1);
    }
}
