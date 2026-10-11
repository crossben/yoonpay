package dev.yoonpay.server.api;

import dev.yoonpay.server.checkout.CheckoutController;
import dev.yoonpay.server.checkout.CheckoutFilter;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The public receipt of a paid invoice (ADR-0027), and what the checkout shows of an invoice. */
class ReceiptTest extends BillingTestSupport {

    private Response receipt(String invoiceId, String token) {
        return send(null, "GET", "/checkout/api/receipts/" + invoiceId, null, null,
                token == null ? Map.of() : Map.of(CheckoutController.TOKEN_HEADER, token));
    }

    private static String tokenOf(String url) {
        return URI.create(url).getQuery().substring(2);
    }

    @Test
    void a_paid_invoice_has_a_receipt_with_its_number_plan_and_period() {
        String id = subscribe(monthly).text("id");
        JsonNode open = sub(id).json().path("latest_invoice");
        assertThat(open.path("receipt_url").isNull()).as("no receipt before payment").isTrue();
        payInvoice(open);

        JsonNode paid = get("shop", "/v1/invoices/" + open.path("id").asString()).json();
        String url = paid.path("receipt_url").asString();
        assertThat(url).contains("/checkout/receipts/" + paid.path("id").asString() + "?t=");

        Response r = receipt(paid.path("id").asString(), tokenOf(url));
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(r.text("number")).isEqualTo(paid.path("number").asString());
        assertThat(r.text("plan")).isEqualTo("Pro");
        assertThat(r.text("interval")).isEqualTo("month");
        assertThat(r.json().path("amount").asLong()).isEqualTo(15000);
        assertThat(r.text("method")).isEqualTo("wave");
        assertThat(r.text("period_start")).isEqualTo(paid.path("period_start").asString());
        assertThat(r.body()).doesNotContain("771234567").doesNotContain("receipt_token").doesNotContain("external_id");
    }

    @Test
    void the_receipt_page_is_served_with_the_checkout_rules() {
        String id = subscribe(monthly).text("id");
        payInvoice(sub(id).json().path("latest_invoice"));
        String url = sub(id).json().path("latest_invoice").path("receipt_url").asString();

        Response page = send(null, "GET", URI.create(url).getRawPath() + "?" + URI.create(url).getRawQuery(), null, null, Map.of());

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body()).contains("receipt.js");
        assertThat(page.headers().get("Content-Security-Policy")).containsExactly(CheckoutFilter.CSP);
        assertThat(page.headers().get("Cache-Control")).containsExactly("no-store");
    }

    @Test
    void wrong_token_unknown_id_and_unpaid_invoice_are_the_same_404() {
        String id = subscribe(monthly).text("id");
        JsonNode open = sub(id).json().path("latest_invoice");
        String invoiceId = open.path("id").asString();
        String token = jdbc.sql("SELECT receipt_token FROM invoices WHERE id = :id").param("id", invoiceId)
                .query(String.class).single();

        Response unpaid = receipt(invoiceId, token);
        Response wrong = receipt(invoiceId, "nope");
        Response missing = receipt("in_0000", token);
        Response none = receipt(invoiceId, null);

        for (Response r : new Response[] {unpaid, wrong, missing, none}) {
            assertThat(r.status()).isEqualTo(404);
            assertThat(r.text("detail")).isEqualTo(unpaid.text("detail"));
            assertThat(r.text("code")).isEqualTo("resource_not_found");
        }
        assertThat(send(null, "GET", "/checkout/receipts/" + invoiceId + "?t=nope", null, null, Map.of()).status()).isEqualTo(404);
    }

    @Test
    void the_checkout_of_an_invoice_shows_the_plan_and_after_payment_the_receipt() {
        String id = subscribe(monthly).text("id");
        JsonNode invoice = sub(id).json().path("latest_invoice");
        String paymentId = invoice.path("payment").asString();
        Map<String, String> header = Map.of(CheckoutController.TOKEN_HEADER, tokenOf(invoice.path("checkout_url").asString()));

        JsonNode before = send(null, "GET", "/checkout/api/" + paymentId, null, null, header).json().path("billing");
        assertThat(before.path("plan").asString()).isEqualTo("Pro");
        assertThat(before.path("invoice_number").asString()).isEqualTo(invoice.path("number").asString());
        assertThat(before.path("interval").asString()).isEqualTo("month");
        assertThat(before.path("receipt_url").isNull()).isTrue();

        payInvoice(invoice);

        JsonNode after = send(null, "GET", "/checkout/api/" + paymentId, null, null, header).json().path("billing");
        assertThat(after.path("receipt_url").asString()).startsWith("/checkout/receipts/" + invoice.path("id").asString());
    }

    @Test
    void an_ordinary_hosted_payment_has_no_billing_on_its_checkout() {
        Response created = post("shop", "/v1/payments", Map.of("amount", 5000, "currency", "XOF", "country", "SN",
                "checkout", "hosted", "reference", "order_" + java.util.UUID.randomUUID(),
                "return_url", "https://shop.example/orders/1"));
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        String url = created.text("checkout_url");
        JsonNode view = send(null, "GET", "/checkout/api/" + created.text("id"), null, null,
                Map.of(CheckoutController.TOKEN_HEADER, tokenOf(url))).json();
        assertThat(view.path("billing").isNull() || view.path("billing").isMissingNode()).isTrue();
    }
}
