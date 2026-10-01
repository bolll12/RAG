package com.pingan.rag;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JevClientTest {
    @TempDir Path temp;
    HttpServer server;
    String body, received, authorization, endpoint;
    int status = 200;
    boolean rejectBatches;
    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/gateway/typesafe/v1/systemone", exchange -> {
            received = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (rejectBatches) {
                if (Json.read(received).path("state").path("passages").size() > 1) {
                    status = 422; body = "{\"detail\":{\"code\":\"token_budget_exceeded\"}}";
                } else {
                    status = 200; body = "{\"answers\":{\"candidate_0\":{\"type\":\"noul\",\"noul\":0.95}}}";
                }
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/gateway/typesafe/v1/systemone";
    }
    @AfterEach void close() { server.stop(0); }
    Map<String,String> config() {
        return Map.of("RAG_JEV_URL", endpoint, "RAG_JEV_API_KEY", "test-secret", "RAG_DB", temp.resolve("test.db").toString());
    }
    List<Map<String,Object>> candidates() {
        return List.of(Json.map("id", "a", "title", "A", "text", "RAG 课程预告"),
                Json.map("id", "b", "title", "B", "text", "RAG 是检索增强生成。"));
    }
    @Test void contractFilterAndRanking() {
        body = "{\"answers\":{\"candidate_0\":{\"type\":\"noul\",\"noul\":0.1},\"candidate_1\":{\"type\":\"noul\",\"noul\":0.95}}}";
        var report = new LinkedHashMap<String,Object>();
        var result = new JevClient(JevSettings.from(config())).rerank("什么是 RAG？", candidates(), 1, report);
        assertEquals(true, report.get("called"));
        assertEquals(2, report.get("candidate_count"));
        assertEquals(1, report.get("passed_count"));
        assertEquals(2, ((List<?>)report.get("judgments")).size());
        assertFalse(Json.write(report).contains("test-secret"));
        assertEquals(1, result.size()); assertEquals("b", result.getFirst().get("id"));
        assertEquals(0.95, result.getFirst().get("jev_probability"));
        var request = Json.read(received);
        assertEquals("bocha-jev-v1", request.path("model").asText());
        assertEquals(2, request.path("questions").size());
        assertEquals(2, request.path("state").path("passages").size());
        assertEquals("Bearer test-secret", authorization);
        assertFalse(candidates().get(1).containsKey("jev_probability"));
    }
    @Test void tokenLimitSplitsBatchWithoutLosingCandidates() {
        rejectBatches = true;
        var report = new LinkedHashMap<String,Object>();
        var result = new JevClient(JevSettings.from(config())).rerank("RAG", candidates(), 2, report);
        assertEquals(List.of("a", "b"), result.stream().map(h -> h.get("id")).toList());
        assertEquals(3, report.get("request_count"));
        assertEquals(2, report.get("passed_count"));
        assertEquals(1, Json.read(received).path("state").path("passages").size());
        assertTrue(Json.read(received).path("questions").path("candidate_0").path("instructions").asText().contains("passages[0]"));
    }
    @Test void singleTooLongCandidateGivesActionableError() {
        status = 422; body = "{\"detail\":{\"code\":\"token_budget_exceeded\"}}";
        var error = assertThrows(ModelException.class, () -> new JevClient(JevSettings.from(config()))
                .rerank("RAG", candidates().subList(0, 1), 1));
        assertTrue(error.getMessage().contains("减小切片长度"));
    }
    @Test void failuresNeverSilentlyUseUnjudgedEvidence() {
        var client = new JevClient(JevSettings.from(config()));
        for (String response : List.of("{}", "not-json", "{\"answers\":{\"candidate_0\":{\"type\":\"noul\",\"noul\":2}}}")) {
            body = response;
            assertThrows(ModelException.class, () -> client.rerank("RAG", candidates(), 1));
        }
        status = 401; body = "secret gateway details";
        var error = assertThrows(ModelException.class, () -> client.rerank("RAG", candidates(), 1));
        assertTrue(error.getMessage().contains("401")); assertFalse(error.getMessage().contains("secret"));
    }
    @Test void disabledAndEmptySkipNetworkAndConfigHidesKey() {
        var off = new JevClient(JevSettings.from(Map.of()));
        assertEquals(1, off.rerank("RAG", candidates(), 1).size());
        assertTrue(new JevClient(JevSettings.from(config())).rerank("RAG", List.of(), 3).isEmpty());
        assertNull(received);
        assertFalse(JevSettings.from(config()).toString().contains("test-secret"));
        assertThrows(IllegalArgumentException.class, () -> JevSettings.from(Map.of("RAG_JEV_THRESHOLD", "NaN")));
    }
    @Test void serviceFiltersBeforeTopKAndRefusesWhenAllRejected() {
        body = "{\"answers\":{\"candidate_0\":{\"type\":\"noul\",\"noul\":0.1},\"candidate_1\":{\"type\":\"noul\",\"noul\":0.95}}}";
        var settings = RagSettings.from(config());
        var store = new RagStore(settings);
        var rag = new RagService(settings, store, new CompatibleModelClient(settings));
        rag.ingest("苹果采购规定每箱十二元。", "A", "a.md", "default");
        rag.ingest("苹果采购规定每箱二十元。", "B", "b.md", "default");
        assertEquals("b.md", rag.search("苹果采购规定", "default", 1).getFirst().get("source"));
        body = "{\"answers\":{\"candidate_0\":{\"type\":\"noul\",\"noul\":0.1},\"candidate_1\":{\"type\":\"noul\",\"noul\":0.2}}}";
        var result = rag.ask("苹果采购规定", "default", 1);
        assertEquals("refused", result.get("mode"));
        assertEquals("bocha-jev-v1", store.trace((String)result.get("trace_id")).get("jev_model"));
    }
}
