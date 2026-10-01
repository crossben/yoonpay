package dev.yoonpay.spring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * A verified event from Yoon. Delivery is at-least-once and unordered: deduplicate on
 * {@link #id()} and act on the state in {@link #object()} (or re-fetch it), not on arrival order.
 * {@link #type()} is a plain string, so an event type added by a newer server never breaks parsing.
 *
 * @param id      e.g. evt_0199…
 * @param type    e.g. payment.succeeded, refund.succeeded, payout.paid
 * @param object  the payment, refund or payout as the API returns it
 * @param payload the whole event
 */
public record YoonEvent(String id, String type, JsonNode object, JsonNode payload) {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** @throws IllegalArgumentException if the body is not a Yoon event */
    public static YoonEvent fromJson(byte[] rawBody) {
        JsonNode payload;
        try {
            payload = JSON.readTree(rawBody);
        } catch (IOException e) {
            throw new IllegalArgumentException("Not a Yoon event", e);
        }
        if (payload == null || !payload.path("id").isTextual() || !payload.path("type").isTextual()
                || !payload.path("data").path("object").isObject()) {
            throw new IllegalArgumentException("Not a Yoon event");
        }
        return new YoonEvent(payload.get("id").asText(), payload.get("type").asText(),
                payload.get("data").get("object"), payload);
    }
}
