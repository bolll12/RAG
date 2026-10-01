package com.pingan.rag;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class CompatibleModelClientTest {
    HttpServer server;
    CompatibleModelClient client;
    AtomicReference<String> response = new AtomicReference<>();
    AtomicReference<String> auth = new AtomicReference<>();
    AtomicReference<String> request = new AtomicReference<>();
    @BeforeEach void init() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        client = new CompatibleModelClient(RagSettings.from(Map.of("RAG_EMBED_BASE_URL",base,"RAG_CHAT_BASE_URL",base,
                "RAG_EMBED_MODEL","embedding-test","RAG_CHAT_MODEL","chat-test","RAG_EMBED_API_KEY","test-key")));
    }
    @AfterEach void close() { server.stop(0); }
    @Test void normalizationAndRequestContract() {
        response.set("{\"data\":[{\"index\":1,\"embedding\":[0,5]},{\"index\":0,\"embedding\":[3,4]}]}");
        var vectors = client.embed(List.of("第一段", "第二段"));
        assertArrayEquals(new double[]{0.6,0.8}, vectors.getFirst(), 0.00001);
        assertEquals("Bearer test-key", auth.get());
        assertEquals("embedding-test", Json.read(request.get()).get("model").asText());
    }
    @Test void invalidVectorsFailClosed() {
        for (String value : List.of("{\"data\":[]}", "{\"data\":[{\"index\":0,\"embedding\":[0,0]}]}",
                "{\"data\":[{\"index\":9,\"embedding\":[1,0]}]}")) {
            response.set(value); assertThrows(ModelException.class, () -> client.embed(List.of("test")));
        }
    }
    @Test void completionAndMalformedResponse() {
        response.set("{\"choices\":[{\"message\":{\"content\":\"回答 [1]\"}}]}");
        assertEquals("回答 [1]", client.answer("问题", "证据"));
        assertEquals("chat-test", Json.read(request.get()).get("model").asText());
        response.set("{\"choices\":[]}");
        assertThrows(ModelException.class, () -> client.answer("问题", "证据"));
    }
}
