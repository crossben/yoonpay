package dev.yoonpay.provider.support;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

import java.util.Optional;

/** Lenient JSON reading for provider answers: a malformed body is "no usable answer", never an exception. */
public final class Json {

    public static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private Json() {
    }

    public static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return MissingNode.getInstance();
        }
        try {
            return MAPPER.readTree(body);
        } catch (JacksonException e) {
            return MissingNode.getInstance();
        }
    }

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    /** Text at a path such as {@code "data.status"}; empty when missing, null or blank. */
    public static Optional<String> text(JsonNode node, String path) {
        JsonNode n = node;
        for (String part : path.split("\\.")) {
            n = n.path(part);
        }
        if (n.isMissingNode() || n.isNull()) {
            return Optional.empty();
        }
        String s = n.isValueNode() ? n.asString() : n.toString();
        return s.isBlank() ? Optional.empty() : Optional.of(s);
    }

    /** An amount that providers send as a number or a numeric string ("5000", "5000.00"). */
    public static Optional<Long> wholeAmount(JsonNode node, String path) {
        return text(node, path).flatMap(s -> {
            try {
                java.math.BigDecimal d = new java.math.BigDecimal(s.trim());
                return d.stripTrailingZeros().scale() <= 0 ? Optional.of(d.longValueExact()) : Optional.empty();
            } catch (NumberFormatException | ArithmeticException e) {
                return Optional.empty();
            }
        });
    }
}
