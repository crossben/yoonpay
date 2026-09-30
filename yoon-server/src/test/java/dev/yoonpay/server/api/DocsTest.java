package dev.yoonpay.server.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocsTest extends ApiTest {

    @Test
    void swagger_ui_renders_the_hand_written_contract() {
        Response page = send(null, "GET", "/docs", null, null);
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body()).contains("SwaggerUIBundle").contains("url: '/openapi.yaml'").doesNotContain("@swagger-ui.version@");

        String asset = page.body().replaceAll("(?s).*src=\"(/webjars/[^\"]+)\".*", "$1");
        assertThat(send(null, "GET", asset, null, null).status()).isEqualTo(200);

        assertThat(page.body()).contains("/favicon.svg");
        Response icon = send(null, "GET", "/favicon.svg", null, null);
        assertThat(icon.status()).isEqualTo(200);
        assertThat(icon.body()).contains("<svg");

        Response spec = send(null, "GET", "/openapi.yaml", null, null);
        assertThat(spec.status()).isEqualTo(200);
        assertThat(spec.body()).startsWith("openapi: 3.1.0");
    }
}
