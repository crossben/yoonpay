package dev.yoonpay.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignatureTest {

    private static JsonNode vector() throws Exception {
        return new ObjectMapper().readTree(Files.readString(Path.of("..", "..", "api", "test-vectors", "webhook-signature.json")));
    }

    @Test
    void the_shared_test_vector_verifies() throws Exception {
        JsonNode v = vector();

        assertThat(WebhookSignature.verify(v.path("secret").asText(), v.path("header").asText(), v.path("body").asText(),
                v.path("timestamp").asLong())).isTrue();
    }

    @Test
    void tampering_a_wrong_secret_or_a_stale_timestamp_fails() throws Exception {
        JsonNode v = vector();
        String secret = v.path("secret").asText();
        String header = v.path("header").asText();
        String body = v.path("body").asText();
        long t = v.path("timestamp").asLong();

        assertThat(WebhookSignature.verify(secret, header, body + " ", t)).isFalse();
        assertThat(WebhookSignature.verify("other", header, body, t)).isFalse();
        assertThat(WebhookSignature.verify(secret, header, body, t + 301)).isFalse();
        assertThat(WebhookSignature.verify(secret, "garbage", body, t)).isFalse();
    }
}
