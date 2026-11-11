package dev.testtr.maven;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResponsesDraftModelTest {
    @Test void requestUsesStructuredOutputAndExtractsMessageText() throws Exception {
        var request = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = """
                    {"status":"completed","output":[{"type":"reasoning"},{"type":"message","content":[
                    {"type":"output_text","text":"{\\"drafts\\":[],\\"review\\":[]}"}]}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var result = new ResponsesDraftModel("configured-model", "test-key", 1000,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/responses")).respond("prompt");
            assertTrue(result.path("drafts").isArray());
            var body = new ObjectMapper().readTree(request.get());
            assertEquals("configured-model", body.path("model").asText());
            assertFalse(body.path("store").asBoolean());
            assertEquals("json_schema", body.at("/text/format/type").asText());
            assertTrue(body.at("/text/format/strict").asBoolean());
            assertEquals("Bearer test-key", authorization.get());
        } finally { server.stop(0); }
    }

    @Test void failuresNeverEchoProviderPayload() throws Exception {
        for (String response : new String[]{"HTTP_ERROR", "invalid test-key", "{\"status\":\"incomplete\",\"secret\":\"test-key\"}",
                "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"refusal\",\"refusal\":\"test-key\"}]}]}"}) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(response.equals("HTTP_ERROR") ? 401 : 200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            try {
                var model = new ResponsesDraftModel("configured-model", "test-key", 1000,
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"));
                var error = assertThrows(java.io.IOException.class, () -> model.respond("prompt"));
                assertFalse(error.getMessage().contains("test-key"));
            } finally { server.stop(0); }
        }
    }

    @Test void missingConfigurationFailsBeforeNetworkRequest() {
        assertThrows(IllegalArgumentException.class, () -> new ResponsesDraftModel("", "test-key", 1000));
        assertThrows(IllegalArgumentException.class, () -> new ResponsesDraftModel("model", null, 1000));
        assertThrows(IllegalArgumentException.class, () -> new ResponsesDraftModel("model", "test-key", 100000));
    }
}
