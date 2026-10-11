package dev.yoonpay.provider.support;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Over plain http:// the JDK client asks for an HTTP/2 upgrade unless told otherwise; some servers
 * (uvicorn, for one) answer that with 400. Provider calls must use plain HTTP/1.1.
 */
class ProviderHttpVersionTest {

    private HttpServer server;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            boolean upgrade = exchange.getRequestHeaders().containsKey("Upgrade");
            byte[] body = (upgrade ? "{\"error\":\"unsupported upgrade\"}" : "{\"ok\":true}").getBytes();
            exchange.sendResponseHeaders(upgrade ? 400 : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/x");
    }

    @Test
    void requests_never_ask_for_a_protocol_upgrade() {
        ProviderHttp http = ProviderHttp.withDefaults();

        assertThat(http.get(uri(), Map.of())).isEqualTo(new ProviderHttp.Result.Answered(200, "{\"ok\":true}"));
        assertThat(http.postJson(uri(), Map.of(), "{}")).isEqualTo(new ProviderHttp.Result.Answered(200, "{\"ok\":true}"));
    }
}
