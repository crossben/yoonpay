package dev.yoonpay.server;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Plays the application's webhook endpoint: records what Yoon sends, answers a chosen status. */
public final class TestWebhookReceiver {

    public static final String SECRET = "test-webhook-secret-0123456789abcdef";

    public record Delivery(Map<String, List<String>> headers, String body) {
        public String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().getFirst()).findFirst().orElse(null);
        }
    }

    private final HttpServer server;
    private final List<Delivery> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(200);

    public TestWebhookReceiver() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/hooks", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            received.add(new Delivery(Map.copyOf(exchange.getRequestHeaders()), new String(body, StandardCharsets.UTF_8)));
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hooks";
    }

    public List<Delivery> received() {
        return List.copyOf(received);
    }

    public void respondWith(int httpStatus) {
        status.set(httpStatus);
    }

    public void reset() {
        received.clear();
        status.set(200);
    }
}
