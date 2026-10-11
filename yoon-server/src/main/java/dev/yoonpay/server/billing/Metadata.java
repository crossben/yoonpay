package dev.yoonpay.server.billing;

import dev.yoonpay.server.web.ApiProblem;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Validation and storage of free-form JSON fields: `metadata` (strings) and `features` (any JSON). */
public final class Metadata {

    public static final int MAX_KEYS = 20;
    public static final int MAX_KEY_LENGTH = 40;
    public static final int MAX_VALUE_LENGTH = 500;
    /** A plan's `features` is stored as given; keep it bounded. */
    public static final int MAX_FEATURES_BYTES = 16_384;

    private Metadata() {
    }

    /** The metadata as stored JSON text; "{}" when absent. Refuses anything but ≤ 20 string pairs. */
    public static String metadata(JsonNode node, ObjectMapper json) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "{}";
        }
        if (!node.isObject()) {
            throw ApiProblem.invalid("metadata must be an object of strings");
        }
        if (node.size() > MAX_KEYS) {
            throw ApiProblem.invalid("metadata has more than " + MAX_KEYS + " keys");
        }
        for (var e : node.properties()) {
            if (e.getKey().isEmpty() || e.getKey().length() > MAX_KEY_LENGTH) {
                throw ApiProblem.invalid("metadata keys are 1 to " + MAX_KEY_LENGTH + " characters");
            }
            if (!e.getValue().isString() || e.getValue().asString().length() > MAX_VALUE_LENGTH) {
                throw ApiProblem.invalid("metadata values are strings of at most " + MAX_VALUE_LENGTH + " characters");
            }
        }
        return json.writeValueAsString(node);
    }

    /** A plan's features as stored JSON text; "{}" when absent. Any JSON object, bounded in size. */
    public static String features(JsonNode node, ObjectMapper json) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "{}";
        }
        if (!node.isObject()) {
            throw ApiProblem.invalid("features must be an object");
        }
        String text = json.writeValueAsString(node);
        if (text.length() > MAX_FEATURES_BYTES) {
            throw ApiProblem.invalid("features is larger than " + MAX_FEATURES_BYTES + " bytes");
        }
        return text;
    }

    public static JsonNode read(String stored, ObjectMapper json) {
        if (stored == null) {
            return json.createObjectNode();
        }
        return json.readTree(stored);
    }
}
