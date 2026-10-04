package dev.yoonpay.server.dashboard;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Static checks on the dashboard sources: API data is only ever written as text, nothing is
 * loaded from another origin at runtime, and the token never leaves sessionStorage and the
 * Authorization header.
 */
class DashboardAssetsTest {

    private static final Path DIR = Path.of("src/main/resources/static/dashboard");

    private static String read(String name) throws IOException {
        return Files.readString(DIR.resolve(name));
    }

    @Test
    void javascript_never_writes_html() throws IOException {
        String js = read("dashboard.js");
        assertThat(js).doesNotContain("innerHTML").doesNotContain("outerHTML").doesNotContain("insertAdjacentHTML")
                .doesNotContain("document.write").doesNotContain("eval(").doesNotContain("new Function")
                .doesNotContain("createContextualFragment").doesNotContain("DOMParser");
        assertThat(js).contains("textContent");
    }

    @Test
    void the_token_stays_in_session_storage_and_the_authorization_header() throws IOException {
        String js = read("dashboard.js");
        assertThat(js).doesNotContain("localStorage").doesNotContain("document.cookie");
        assertThat(js).contains("sessionStorage").contains("Authorization: `Bearer ${t}`");
        assertThat(js).doesNotContainPattern("[?&](token|access_token)=");
    }

    @Test
    void nothing_is_loaded_from_another_origin() throws IOException {
        for (String name : new String[]{"index.html", "dashboard.js", "dashboard.css"}) {
            assertThat(read(name)).as(name).doesNotContainPattern("(src|href|url)\\s*[=(]\\s*[\"']?(https?:)?//")
                    .doesNotContain("@import");
        }
        String html = read("index.html");
        assertThat(html).doesNotContainPattern("<script(?![^>]*\\bsrc=)").doesNotContain(" style=").doesNotContainPattern("\\son[a-z]+\\s*=");
    }
}
