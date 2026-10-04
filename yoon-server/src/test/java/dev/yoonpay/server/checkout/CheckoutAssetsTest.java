package dev.yoonpay.server.checkout;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Static checks on the hosted checkout page (ADR-0024): server data is only ever written as text,
 * nothing is loaded from another origin, the token goes only to this origin in its header, and
 * nothing is kept in the browser.
 */
class CheckoutAssetsTest {

    private static final Path DIR = Path.of("src/main/resources/static/checkout");

    private static String read(String name) throws IOException {
        return Files.readString(DIR.resolve(name));
    }

    @Test
    void javascript_never_writes_html() throws IOException {
        String js = read("checkout.js");
        assertThat(js).doesNotContain("innerHTML").doesNotContain("outerHTML").doesNotContain("insertAdjacentHTML")
                .doesNotContain("document.write").doesNotContain("eval(").doesNotContain("new Function")
                .doesNotContain("createContextualFragment").doesNotContain("DOMParser").doesNotContain("setAttribute('on");
        assertThat(js).contains("textContent");
    }

    @Test
    void the_token_is_sent_only_to_this_origin_and_nothing_is_stored() throws IOException {
        String js = read("checkout.js");
        assertThat(js).doesNotContain("localStorage").doesNotContain("sessionStorage").doesNotContain("document.cookie")
                .doesNotContain("indexedDB");
        assertThat(js).contains("'Yoon-Checkout-Token': token").contains("credentials: 'omit'");
        // Every fetch goes to a same-origin path; navigation targets are checked to be http(s).
        assertThat(js).containsPattern("fetch\\(path,").doesNotContainPattern("fetch\\(['\"`]https?:");
        assertThat(js).contains("u.protocol === 'https:' || u.protocol === 'http:'");
    }

    @Test
    void nothing_is_loaded_from_another_origin() throws IOException {
        for (String name : new String[]{"index.html", "checkout.js", "checkout.css"}) {
            assertThat(read(name)).as(name).doesNotContainPattern("(src|href|url)\\s*[=(]\\s*[\"']?(https?:)?//")
                    .doesNotContain("@import");
        }
        String html = read("index.html");
        assertThat(html).doesNotContainPattern("<script(?![^>]*\\bsrc=)").doesNotContain(" style=")
                .doesNotContainPattern("\\son[a-z]+\\s*=");
    }

    @Test
    void the_page_is_accessible_and_bilingual() throws IOException {
        String html = read("index.html");
        assertThat(html).contains("<html lang=\"en\">").contains("name=\"viewport\"").contains("aria-live=\"polite\"")
                .contains("<label for=\"phone\"").contains("<label for=\"alias\"").contains("<legend");
        String js = read("checkout.js");
        assertThat(js).contains("fr: {").contains("en: {").contains("navigator.languages");
        String css = read("checkout.css");
        assertThat(css).contains("prefers-color-scheme: dark").contains(":focus-visible");
    }
}
