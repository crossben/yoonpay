package dev.yoonpay.server.api;

import dev.yoonpay.server.billing.BillingSweep;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The billing sweep (ADR-0026): reminders, period ends, grace, unpaid first invoices. Time moves by
 * ageing rows in SQL (as the reconciliation tests do), never by sleeping.
 */
class BillingSweepTest extends BillingTestSupport {

    @Autowired
    BillingSweep sweep;

    /** An active, paid subscription. */
    private String active() {
        String id = subscribe(monthly).text("id");
        payInvoice(sub(id).json().path("latest_invoice"));
        return id;
    }

    /** Puts the current period's end `days` from now (negative: in the past), keeping a 30-day period. */
    private void periodEndsIn(String id, int days) {
        jdbc.sql("""
                        UPDATE subscriptions
                        SET current_period_start = now() + make_interval(days => :d - 30),
                            current_period_end = now() + make_interval(days => :d)
                        WHERE id = :id""")
                .param("d", days).param("id", id).update();
    }

    private long openInvoices(String id) {
        return jdbc.sql("SELECT count(*) FROM invoices WHERE subscription_id = :s AND status = 'OPEN'")
                .param("s", id).query(Long.class).single();
    }

    // ------------------------------------------------------------------ reminders

    @Test
    void a_renewal_reminder_is_sent_once_when_the_period_end_is_near() {
        String id = active();
        periodEndsIn(id, 10);
        sweep.remind();
        assertThat(eventTypes(id)).doesNotContain("subscription.renewal_upcoming");

        periodEndsIn(id, 2);
        sweep.remind();
        sweep.remind();

        assertThat(eventTypes(id).stream().filter("subscription.renewal_upcoming"::equals)).hasSize(1);
    }

    @Test
    void a_subscription_ending_on_request_gets_no_renewal_reminder() {
        String id = active();
        post("shop", "/v1/subscriptions/" + id + "/cancel", Map.of());
        periodEndsIn(id, 1);

        sweep.remind();

        assertThat(eventTypes(id)).doesNotContain("subscription.renewal_upcoming");
    }

    // ------------------------------------------------------------------ period end

    @Test
    void an_unpaid_period_end_makes_it_past_due_still_entitled_with_an_invoice_to_pay() {
        String id = active();
        periodEndsIn(id, -1);

        sweep.endPeriods();

        Response s = sub(id);
        assertThat(s.text("status")).isEqualTo("past_due");
        assertThat(s.json().path("entitled").asBoolean()).as("grace is entitled").isTrue();
        assertThat(openInvoices(id)).isEqualTo(1);
        assertThat(s.json().path("latest_invoice").path("kind").asString()).isEqualTo("renewal");
        assertThat(eventTypes(id)).endsWith("subscription.past_due");
    }

    @Test
    void a_cancellation_at_period_end_takes_effect_when_the_period_ends() {
        String id = active();
        post("shop", "/v1/subscriptions/" + id + "/cancel", Map.of());
        periodEndsIn(id, -1);

        sweep.endPeriods();

        Response s = sub(id);
        assertThat(s.text("status")).isEqualTo("canceled");
        assertThat(s.json().path("canceled_at").isNull()).isFalse();
        assertThat(openInvoices(id)).isZero();
        assertThat(eventTypes(id)).endsWith("subscription.canceled");
    }

    @Test
    void a_scheduled_change_applies_at_the_period_end_and_the_invoice_bills_it() {
        String id = active();
        post("shop", "/v1/subscriptions/" + id + "/change", Map.of("price", yearly));
        periodEndsIn(id, -1);

        sweep.endPeriods();

        Response s = sub(id);
        assertThat(s.json().path("price").path("id").asString()).isEqualTo(yearly);
        assertThat(s.json().path("pending_change").isNull()).isTrue();
        assertThat(s.json().path("latest_invoice").path("amount_due").asLong()).isEqualTo(150000);
    }

    @Test
    void a_subscription_renewed_meanwhile_is_left_alone() {
        String id = active();
        periodEndsIn(id, 5);   // the payment of a renewal moved the end into the future

        assertThat(sweep.endPeriods()).isZero();
        assertThat(sub(id).text("status")).isEqualTo("active");
    }

    // ------------------------------------------------------------------ grace and expiry

    @Test
    void grace_ends_in_expiry_but_not_before() {
        String id = active();                 // default grace: 3 days
        periodEndsIn(id, -2);
        sweep.endPeriods();
        sweep.endGrace();
        assertThat(sub(id).text("status")).isEqualTo("past_due");

        periodEndsIn(id, -4);
        sweep.endGrace();

        Response s = sub(id);
        assertThat(s.text("status")).isEqualTo("expired");
        assertThat(s.json().path("entitled").asBoolean()).isFalse();
        assertThat(openInvoices(id)).as("still payable: a late payment re-activates").isEqualTo(1);
        assertThat(eventTypes(id)).endsWith("subscription.expired");
    }

    @Test
    void with_no_grace_one_pass_goes_from_active_to_expired() {
        String id = post("shop", "/v1/subscriptions", Map.of("customer", customer, "price", monthly,
                "grace_period_days", 0, "return_url", "https://shop.example/billing")).text("id");
        payInvoice(sub(id).json().path("latest_invoice"));
        periodEndsIn(id, -1);

        sweep.sweep();

        assertThat(sub(id).text("status")).isEqualTo("expired");
    }

    @Test
    void an_expired_subscription_paid_late_comes_back() {
        String id = active();
        periodEndsIn(id, -10);
        sweep.sweep();
        assertThat(sub(id).text("status")).isEqualTo("expired");

        payInvoice(renew(id).json());

        assertThat(sub(id).text("status")).isEqualTo("active");
        assertThat(eventTypes(id)).endsWith("subscription.activated");
    }

    @Test
    void a_first_invoice_left_unpaid_too_long_expires_the_subscription() {
        String fresh = subscribe(monthly).text("id");
        String old = subscribe(monthly).text("id");
        jdbc.sql("UPDATE subscriptions SET created_at = now() - interval '8 days' WHERE id = :id").param("id", old).update();

        sweep.expireIncomplete();

        assertThat(sub(old).text("status")).isEqualTo("expired");
        assertThat(sub(fresh).text("status")).isEqualTo("incomplete");
    }

    @Test
    void running_the_sweep_twice_changes_nothing_more() {
        String id = active();
        periodEndsIn(id, -1);
        sweep.sweep();
        String status = sub(id).text("status");
        int events = eventTypes(id).size();

        sweep.sweep();

        assertThat(sub(id).text("status")).isEqualTo(status);
        assertThat(eventTypes(id)).hasSize(events);
        assertThat(openInvoices(id)).isEqualTo(1);
    }
}
