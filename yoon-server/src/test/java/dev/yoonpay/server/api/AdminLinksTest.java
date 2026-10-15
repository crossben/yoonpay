package dev.yoonpay.server.api;

import dev.yoonpay.server.auth.Applications;
import dev.yoonpay.server.link.LinksCommand;
import dev.yoonpay.server.link.PaymentLinkService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Payment links for operators (ADR-0030): admin API, CLI, exports. */
class AdminLinksTest extends ApiTest {

    @Autowired
    Applications applications;

    @Autowired
    PaymentLinkService links;

    private String base() {
        return "/admin/v1/applications/" + app("shop").id() + "/payment_links";
    }

    private Response adminPost(String path, Object body) {
        return send("admin:" + ADMIN_TOKEN, "POST", path, body, "k-" + UUID.randomUUID());
    }

    @Test
    void an_operator_creates_lists_and_archives_a_link() {
        Response created = adminPost(base(), Map.of("amount", 5000, "currency", "XOF", "country", "SN", "description", "Thiéboudienne"));
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        String id = created.text("id");

        assertThat(admin("GET", base(), null).body()).contains(id);

        Response archived = adminPost(base() + "/" + id + "/archive", null);
        assertThat(archived.status()).as(archived.body()).isEqualTo(200);
        assertThat(archived.json().path("active").asBoolean()).isFalse();
        assertThat(get("shop", "/v1/payment_links/" + id).json().path("active").asBoolean()).isFalse();
    }

    @Test
    void a_double_submit_creates_one_link() {
        String key = "k-" + UUID.randomUUID();
        Map<String, Object> body = Map.of("amount", 1000, "currency", "XOF", "country", "SN", "description", "Bissap");

        Response first = send("admin:" + ADMIN_TOKEN, "POST", base(), body, key);
        Response again = send("admin:" + ADMIN_TOKEN, "POST", base(), body, key);

        assertThat(again.text("id")).isEqualTo(first.text("id"));
    }

    @Test
    void the_admin_api_needs_the_operator_token() {
        Response r = send("shop", "POST", base(), Map.of("amount", 1000, "currency", "XOF", "country", "SN",
                "description", "x"), "k-" + UUID.randomUUID());
        assertThat(r.status()).isIn(401, 404);
    }

    @Test
    void the_cli_creates_a_link_by_application_name() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

        int code = LinksCommand.run(new String[] {"links", "create", "shop", "5000", "xof", "sn", "Sac", "en", "wax"},
                applications, links, out);

        String printed = bytes.toString(StandardCharsets.UTF_8);
        assertThat(code).as(printed).isZero();
        assertThat(printed).contains("Payment link plink_").contains("/l/").contains("QR code:");
        bytes.reset();
        assertThat(LinksCommand.run(new String[] {"links", "create", "shop", "any", "XOF", "SN", "Don"}, applications, links, out)).isZero();
        assertThat(LinksCommand.run(new String[] {"links", "create", "nope", "5000", "XOF", "SN", "x"}, applications, links, out)).isEqualTo(2);
        assertThat(LinksCommand.run(new String[] {"links", "create", "shop", "-5", "XOF", "SN", "x"}, applications, links, out)).isEqualTo(2);
        assertThat(LinksCommand.run(new String[] {"links", "create", "shop", "5000", "XOF", "SEN", "x"}, applications, links, out)).isEqualTo(2);
        assertThat(LinksCommand.run(new String[] {"links", "create", "shop", "5000"}, applications, links, out)).isEqualTo(2);
    }

    @Test
    void links_and_their_payments_export_as_csv() {
        String id = post("shop", "/v1/payment_links", Map.of("amount", 5000, "currency", "XOF", "country", "SN",
                "description", "Export me")).text("id");

        Response csv = get("shop", "/v1/exports/payment_links.csv");
        Response payments = get("shop", "/v1/exports/payments.csv");

        assertThat(csv.body()).startsWith("id,created_at,description,amount,min_amount,max_amount,currency,country,");
        assertThat(csv.body()).contains(id + ",").contains("Export me");
        assertThat(payments.body().lines().findFirst().orElseThrow()).endsWith(",payment_link");
    }
}
