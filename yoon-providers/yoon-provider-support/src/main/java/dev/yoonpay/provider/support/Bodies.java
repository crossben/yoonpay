package dev.yoonpay.provider.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** Reads a callback body as JSON, or as a form with PHP-style brackets ({@code data[invoice][token]=…}). */
public final class Bodies {

    private Bodies() {
    }

    public static JsonNode parse(byte[] raw, String contentType) {
        String body = new String(raw, StandardCharsets.UTF_8).trim();
        boolean form = contentType != null && contentType.toLowerCase().contains("x-www-form-urlencoded");
        if (!form && (body.startsWith("{") || body.startsWith("["))) {
            return Json.parse(body);
        }
        return form(body);
    }

    static ObjectNode form(String body) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        if (body.isEmpty()) {
            return root;
        }
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String value = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            // data[invoice][token] -> ["data", "invoice", "token"]
            String[] path = key.replace("]", "").split("\\[");
            ObjectNode node = root;
            for (int i = 0; i < path.length - 1; i++) {
                JsonNode child = node.get(path[i]);
                node = child instanceof ObjectNode o ? o : node.putObject(path[i]);
            }
            node.put(path[path.length - 1], value);
        }
        return root;
    }
}
