package dev.yoonpay.server.api;

import dev.yoonpay.server.auth.Applications;
import dev.yoonpay.server.billing.BillingCommand;
import dev.yoonpay.server.billing.BillingSweep;
import dev.yoonpay.server.billing.CompService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Operator billing (ADR-0026, "Operator"): comp, the admin read views, the CLI and the CSV exports. */
class AdminBillingTest extends BillingTestSupport {

    @Autowired
    BillingSweep sweep;

    @Autowired
    Applications applications;

    @Autowired
    CompService compService;

    private String base() {
        return "/admin/v1/applications/" + app("shop").id();
    }

    private Response comp(String path, Map<String, Object> body, String key) {
        return send("admin:" + ADMIN_TOKEN, "POST", base() + path, body, key);
    }

    private Response comp(String path, Map<String, Object> body) {
        return comp(path, body, UUID.randomUUID().toString());
    }

    private static Map<String, Object> periods(String price, int n) {
        return Map.of("price", price, "periods", n, "note", "partner, approved by ops");
    }

    private long payments(String subscriptionId) {
        return jdbc.sql("SELECT count(*) FROM payments WHERE subscription_id = :s AND status = 'SUCCEEDED'")
                .param("s", subscriptionId).query(Long.class).single();
    }

    private long ledgerPostings() {
        return jdbc.sql("SELECT count(*) FROM ledger_postings").query(Long.class).single();
    }

    // ------------------------------------------------------------------ comp

    @Test
    void a_comp_activates_an_unpaid_subscription_without_payment_or_ledger_posting() {
        String id = subscribe(monthly).text("id");
        long postings = ledgerPostings();

        Response r = comp("/subscriptions/" + id + "/comp", periods(monthly, 3));

        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(r.text("status")).isEqualTo("active");
        assertThat(r.json().path("comp").asBoolean()).isTrue();
        Duration length = Duration.between(at(r, "current_period_start"), at(r, "current_period_end"));
        assertThat(length.toDays()).isBetween(89L, 92L);
        assertThat(r.json().path("latest_invoice").path("status").asString()).as("the first invoice is voided").isEqualTo("void");
        assertThat(payments(id)).isZero();
        assertThat(ledgerPostings()).isEqualTo(postings);
        assertThat(eventTypes(id)).endsWith("subscription.activated");

        JsonNodes history = new JsonNodes(admin("GET", base() + "/subscriptions/" + id + "/events", null));
        assertThat(history.last("cause")).isEqualTo("admin");
        assertThat(history.last("detail")).contains("comp 3 period(s)").contains("partner, approved by ops");
    }

    @Test
    void a_comp_on_a_paid_subscription_stacks_after_its_period() {
        String id = subscribe(monthly).text("id");
        payInvoice(sub(id).json().path("latest_invoice"));
        Instant paidEnd = at(sub(id), "current_period_end");

        Response r = comp("/subscriptions/" + id + "/comp", periods(monthly, 1));

        assertThat(at(r, "current_period_start")).isEqualTo(paidEnd);
        assertThat(eventTypes(id)).endsWith("subscription.renewed");
    }

    @Test
    void an_open_comp_has_no_end_and_the_sweep_leaves_it_alone_until_canceled() {
        String id = subscribe(monthly).text("id");

        Response r = comp("/subscriptions/" + id + "/comp", Map.of("price", premium, "open", true, "note", "contact-us plan"));

        assertThat(r.json().path("current_period_end").isNull()).isTrue();
        assertThat(r.json().path("price").path("id").asString()).isEqualTo(premium);
        jdbc.sql("UPDATE subscriptions SET created_at = now() - interval '400 days' WHERE id = :id").param("id", id).update();
        sweep.sweep();
        assertThat(sub(id).text("status")).isEqualTo("active");

        post("shop", "/v1/subscriptions/" + id + "/cancel", Map.of("at_period_end", false));
        assertThat(sub(id).text("status")).isEqualTo("canceled");
    }

    @Test
    void a_customer_comp_creates_their_subscription() {
        Response r = comp("/customers/" + customer + "/comp", periods(yearly, 1));

        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(r.json().path("customer").path("id").asString()).isEqualTo(customer);
        assertThat(r.text("status")).isEqualTo("active");
        assertThat(eventTypes(r.text("id"))).containsExactly("subscription.created", "subscription.activated");
    }

    @Test
    void a_retried_comp_grants_once() {
        String id = subscribe(monthly).text("id");
        String key = UUID.randomUUID().toString();

        Response first = comp("/subscriptions/" + id + "/comp", periods(monthly, 1), key);
        Response again = comp("/subscriptions/" + id + "/comp", periods(monthly, 1), key);

        assertThat(again.text("current_period_end")).isEqualTo(first.text("current_period_end"));
        assertThat(at(sub(id), "current_period_end")).isEqualTo(at(first, "current_period_end"));
    }

    @Test
    void comp_needs_a_note_and_exactly_one_of_periods_and_open() {
        String id = subscribe(monthly).text("id");
        Map<String, Object> both = new HashMap<>(periods(monthly, 1));
        both.put("open", true);

        assertThat(comp("/subscriptions/" + id + "/comp", Map.of("price", monthly, "note", "x")).status()).isEqualTo(400);
        assertThat(comp("/subscriptions/" + id + "/comp", both).status()).isEqualTo(400);
        assertThat(comp("/subscriptions/" + id + "/comp", Map.of("price", monthly, "periods", 1)).status()).isEqualTo(400);
        assertThat(comp("/subscriptions/" + id + "/comp", Map.of("price", monthly, "periods", 0, "note", "x")).status()).isEqualTo(400);
        assertThat(comp("/subscriptions/" + id + "/comp", periods("price_nope", 1)).status()).isEqualTo(422);
        assertThat(comp("/subscriptions/sub_nope/comp", periods(monthly, 1)).status()).isEqualTo(404);
        assertThat(sub(id).text("status")).isEqualTo("incomplete");
    }

    @Test
    void paying_after_a_comp_makes_the_period_paid_again() {
        String id = subscribe(monthly).text("id");
        comp("/subscriptions/" + id + "/comp", periods(monthly, 1));

        payInvoice(renew(id).json());

        assertThat(sub(id).json().path("comp").asBoolean()).isFalse();
    }

    @Test
    void comp_requires_the_operator_token() {
        String id = subscribe(monthly).text("id");
        Response r = send("shop", "POST", base() + "/subscriptions/" + id + "/comp", periods(monthly, 1), "k-" + UUID.randomUUID());
        assertThat(r.status()).isIn(401, 404);
        assertThat(sub(id).text("status")).isEqualTo("incomplete");
    }

    // ------------------------------------------------------------------ CLI

    @Test
    void the_cli_comps_by_application_name() {
        String id = subscribe(monthly).text("id");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

        int code = BillingCommand.run(new String[] {"billing", "comp", "shop", id, monthly, "2", "school", "partner"},
                applications, compService, out);

        assertThat(code).as(bytes.toString(StandardCharsets.UTF_8)).isZero();
        assertThat(bytes.toString(StandardCharsets.UTF_8)).contains("Comped " + id);
        assertThat(sub(id).text("status")).isEqualTo("active");

        bytes.reset();
        assertThat(BillingCommand.run(new String[] {"billing", "comp", "nope", id, monthly, "2", "x"}, applications, compService, out)).isEqualTo(2);
        assertThat(BillingCommand.run(new String[] {"billing", "comp", "shop", id, monthly, "two", "x"}, applications, compService, out)).isEqualTo(2);
        assertThat(BillingCommand.run(new String[] {"billing", "comp", "shop", id, monthly, "1"}, applications, compService, out))
                .as("no note: refused by the service").isEqualTo(1);
    }

    // ------------------------------------------------------------------ read views and exports

    @Test
    void the_admin_views_list_plans_subscriptions_and_invoices() {
        String id = subscribe(monthly).text("id");

        assertThat(admin("GET", base() + "/plans?limit=100", null).body()).contains(monthly);
        assertThat(admin("GET", base() + "/subscriptions?customer=" + customer, null).json().path("data").get(0).path("id").asString())
                .isEqualTo(id);
        assertThat(admin("GET", base() + "/invoices?subscription=" + id, null).json().path("data")).hasSize(1);
        assertThat(admin("GET", "/admin/v1/applications/" + UUID.randomUUID() + "/invoices", null).status()).isEqualTo(404);
    }

    @Test
    void subscriptions_and_invoices_export_as_csv() {
        String id = subscribe(monthly).text("id");
        payInvoice(sub(id).json().path("latest_invoice"));
        String number = sub(id).json().path("latest_invoice").path("number").asString();

        Response subs = get("shop", "/v1/exports/subscriptions.csv");
        Response invs = get("shop", "/v1/exports/invoices.csv");

        assertThat(subs.status()).isEqualTo(200);
        assertThat(subs.body()).startsWith("id,created_at,customer,customer_external_id,plan,price,status,");
        assertThat(subs.body()).contains(id + ",").contains(",active,").contains(",15000,XOF,month,1");
        assertThat(invs.body()).startsWith("id,number,created_at,");
        assertThat(invs.body()).contains("," + number + ",").contains(",first,paid,15000,XOF,");
        assertThat(subs.body() + invs.body()).doesNotContain("771234567");
    }

    /** The events list as returned by a history endpoint. */
    private record JsonNodes(Response r) {
        String last(String field) {
            var data = r.json().isArray() ? r.json() : r.json().path("data");
            return data.get(data.size() - 1).path(field).asString();
        }
    }
}
