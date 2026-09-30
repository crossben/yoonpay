package dev.yoonpay.server.outbox;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The shared vector in api/test-vectors: the PHP and Java clients verify the same one. */
class WebhookSignatureVectorTest {

    @Test
    void the_server_signs_exactly_the_published_test_vector() throws Exception {
        JsonNode v = JsonMapper.builder().build().readTree(Files.readString(Path.of("..", "api", "test-vectors", "webhook-signature.json")));

        String header = WebhookSignature.sign(v.path("secret").asString(), v.path("timestamp").asLong(), v.path("body").asString());

        assertThat(header).isEqualTo(v.path("header").asString());
        assertThat(WebhookSignature.verify(v.path("secret").asString(), header, v.path("body").asString(), v.path("timestamp").asLong())).isTrue();
    }
}
