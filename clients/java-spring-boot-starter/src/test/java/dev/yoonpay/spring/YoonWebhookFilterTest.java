package dev.yoonpay.spring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class YoonWebhookFilterTest {

    private static final String SECRET = "spring-test-webhook-secret-0123456789";

    @RestController
    static class Webhooks {
        final List<String> reached = new ArrayList<>();

        @PostMapping("/yoon/webhook")
        ResponseEntity<Void> handle(YoonEvent event, @RequestBody String body) {
            reached.add(event.id());
            assertThat(body).contains(event.id()); // the controller can still read the raw body
            return switch (event.object().path("id").asText()) {
                case "pay_fail" -> ResponseEntity.status(500).build();
                case "pay_throw" -> throw new IllegalStateException("handler crashed");
                default -> ResponseEntity.noContent().build();
            };
        }

        @PostMapping("/plain")
        ResponseEntity<String> plain(@RequestBody String body) {
            return ResponseEntity.ok(body);
        }
    }

    private Webhooks controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        controller = new Webhooks();
        YoonWebhookFilter filter = new YoonWebhookFilter(SECRET, List.of("/yoon/webhook"), new InMemoryYoonEventStore(Duration.ofDays(3)));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new YoonEventArgumentResolver())
                .addFilters(filter)
                .build();
    }

    static String event(String id, String paymentId) {
        return "{\"id\":\"" + id + "\",\"object\":\"event\",\"type\":\"payment.succeeded\",\"created_at\":\"2026-09-30T10:00:00Z\","
                + "\"data\":{\"object\":{\"id\":\"" + paymentId + "\",\"status\":\"succeeded\"}}}";
    }

    static String sign(String body, long t) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "t=" + t + ",v1=" + HexFormat.of().formatHex(mac.doFinal((t + "." + body).getBytes(StandardCharsets.UTF_8)));
    }

    private ResultActions deliver(String path, String body, String signature) throws Exception {
        return mvc.perform(post(path).contentType("application/json").content(body).header("Yoon-Signature", signature));
    }

    private ResultActions deliver(String body) throws Exception {
        return deliver("/yoon/webhook", body, sign(body, Instant.now().getEpochSecond()));
    }

    @Test
    void aVerifiedEventReachesTheController() throws Exception {
        deliver(event("evt_1", "pay_1")).andExpect(status().isNoContent());
        assertThat(controller.reached).containsExactly("evt_1");
    }

    @Test
    void aBadSignatureIsRefused() throws Exception {
        deliver("/yoon/webhook", event("evt_2", "pay_1"), "t=" + Instant.now().getEpochSecond() + ",v1=" + "0".repeat(64))
                .andExpect(status().isUnauthorized());
        assertThat(controller.reached).isEmpty();
    }

    @Test
    void aStaleSignatureIsRefused() throws Exception {
        String body = event("evt_3", "pay_1");
        deliver("/yoon/webhook", body, sign(body, Instant.now().getEpochSecond() - 301)).andExpect(status().isUnauthorized());
        assertThat(controller.reached).isEmpty();
    }

    @Test
    void aReEncodedBodyIsRefused() throws Exception {
        String body = event("evt_4", "pay_1");
        deliver("/yoon/webhook", body.replace(",", ", "), sign(body, Instant.now().getEpochSecond()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aDuplicateIsAcknowledgedWithoutReachingTheController() throws Exception {
        deliver(event("evt_5", "pay_1")).andExpect(status().isNoContent());
        deliver(event("evt_5", "pay_1")).andExpect(status().isOk()).andExpect(content().string("{\"duplicate\":true}"));
        assertThat(controller.reached).containsExactly("evt_5");
    }

    @Test
    void aFailingControllerIsNotRememberedSoTheRetryReachesIt() throws Exception {
        deliver(event("evt_6", "pay_fail")).andExpect(status().isInternalServerError());
        deliver(event("evt_6", "pay_fail")).andExpect(status().isInternalServerError());
        assertThat(controller.reached).containsExactly("evt_6", "evt_6");
    }

    @Test
    void aCrashingControllerIsNotRememberedEither() throws Exception {
        assertThatThrownBy(() -> deliver(event("evt_7", "pay_throw"))).hasRootCauseMessage("handler crashed");
        assertThatThrownBy(() -> deliver(event("evt_7", "pay_throw"))).hasRootCauseMessage("handler crashed");
        assertThat(controller.reached).containsExactly("evt_7", "evt_7");
    }

    @Test
    void otherPathsAreLeftAlone() throws Exception {
        deliver("/plain", "{}", "nonsense").andExpect(status().isOk()).andExpect(content().string("{}"));
    }

}
