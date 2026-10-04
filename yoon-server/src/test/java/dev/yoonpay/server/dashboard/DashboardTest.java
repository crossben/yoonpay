package dev.yoonpay.server.dashboard;

import dev.yoonpay.server.api.ApiTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** /dashboard is a static page plus assets: served with a strict CSP, holding nothing secret. */
class DashboardTest extends ApiTest {

    @Test
    void serves_the_page_and_its_assets_with_strict_headers() {
        for (String path : new String[]{"/dashboard", "/dashboard/", "/dashboard/index.html"}) {
            Response page = send(null, "GET", path, null, null);
            assertThat(page.status()).as(path).isEqualTo(200);
            assertThat(page.header("Content-Type").orElseThrow()).startsWith("text/html");
            assertThat(page.body()).contains("<title>Yoon operator dashboard</title>")
                    .contains("/dashboard/dashboard.js").contains("/dashboard/dashboard.css")
                    .doesNotContain("<script>").doesNotContain("style=").doesNotContain("http://").doesNotContain("https://");
            assertStrictHeaders(page);
        }
        Response js = send(null, "GET", "/dashboard/dashboard.js", null, null);
        assertThat(js.status()).isEqualTo(200);
        assertThat(js.header("Content-Type").orElseThrow()).contains("javascript");
        assertStrictHeaders(js);
        Response css = send(null, "GET", "/dashboard/dashboard.css", null, null);
        assertThat(css.status()).isEqualTo(200);
        assertThat(css.header("Content-Type").orElseThrow()).startsWith("text/css");
        assertStrictHeaders(css);
    }

    @Test
    void the_page_contains_no_secret_and_needs_no_credentials() {
        for (String path : new String[]{"/dashboard/index.html", "/dashboard/dashboard.js", "/dashboard/dashboard.css"}) {
            String body = send(null, "GET", path, null, null).body();
            assertThat(body).doesNotContain(ADMIN_TOKEN).doesNotContain("yk_");
        }
    }

    private static void assertStrictHeaders(Response r) {
        assertThat(r.header("Content-Security-Policy")).contains(DashboardFilter.CSP);
        assertThat(DashboardFilter.CSP).doesNotContain("unsafe").doesNotContain("http").contains("default-src 'none'")
                .contains("frame-ancestors 'none'");
        assertThat(r.header("X-Content-Type-Options")).contains("nosniff");
        assertThat(r.header("X-Frame-Options")).contains("DENY");
        assertThat(r.header("Referrer-Policy")).contains("no-referrer");
        assertThat(r.header("Cache-Control")).contains("no-store");
    }
}
