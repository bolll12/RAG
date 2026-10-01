package com.pingan.rag;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RagServiceTest {
    @TempDir Path temp;
    RagSettings settings;
    RagStore store;
    FakeModels models;
    RagService rag;
    static class FakeModels implements ModelGateway {
        String response = "知识库支持 PDF。[1]";
        boolean failEmbedding, failAnswer;
        @Override public List<double[]> embed(List<String> texts) {
            if (failEmbedding) throw new ModelException("模拟嵌入失败");
            return texts.stream().map(t -> new double[]{1,0}).toList();
        }
        @Override public String answer(String question, String evidence) {
            if (failAnswer) throw new ModelException("模拟生成超时");
            assertTrue(evidence.contains("citation"));
            return response;
        }
    }
    @BeforeEach void init() {
        settings = RagSettings.from(Map.of("RAG_DB", temp.resolve("rag.db").toString()));
        store = new RagStore(settings); models = new FakeModels(); rag = new RagService(settings, store, new CompatibleModelClient(settings));
    }
    @Test void unicodeOffsetsCoverOriginalText() {
        String text = "# 标题😀\n完整段落。\n".repeat(200);
        boolean[] covered = new boolean[TextProcessing.length(text)];
        for (var part : TextProcessing.chunks(text, 160, 30)) {
            assertEquals(part.text(), TextProcessing.slice(text, part.start(), part.end()));
            assertTrue(TextProcessing.length(part.text()) <= 160);
            for (int i = part.start(); i < part.end(); i++) covered[i] = true;
        }
        for (boolean value : covered) assertTrue(value);
    }
    @Test void updateDeleteAndRestart() {
        var first = rag.ingest("苹果采购标准。", "采购制度", "policy.md", "default");
        assertEquals(true, rag.ingest("苹果采购标准。", "采购制度", "policy.md", "default").get("unchanged"));
        var second = rag.ingest("香蕉入库要求。", "采购制度", "policy.md", "default");
        assertEquals(first.get("document_id"), second.get("document_id"));
        assertEquals(2, second.get("version"));
        assertTrue(rag.search("苹果采购", "default", 5).isEmpty());
        var restarted = new RagService(settings, new RagStore(settings), models);
        assertEquals(2, restarted.search("香蕉入库", "default", 5).getFirst().get("version"));
        assertTrue(store.delete((String) first.get("document_id")));
        assertTrue(restarted.search("香蕉入库", "default", 5).isEmpty());
    }
    @Test void collectionFilterAndRefusal() {
        rag.ingest("苹果采购预算。", "秘密", "private.md", "private");
        assertTrue(rag.search("苹果采购", "default", 5).isEmpty());
        assertEquals("refused", rag.ask("苹果采购", "default", 5).get("mode"));
        assertEquals(1, rag.search("苹果采购", "private", 5).size());
    }
    @Test void extractiveAnswerAndTrace() {
        rag.ingest("知识库支持文本型 PDF。", "格式", "files.md", "default");
        var answer = rag.ask("知识库支持哪些文件", "default", 5);
        assertEquals("extractive", answer.get("mode"));
        var trace = store.trace((String) answer.get("trace_id"));
        assertEquals("ok", trace.get("status"));
        assertFalse(trace.containsKey("question"));
        assertTrue(trace.containsKey("question_hash"));
    }
    @Test void citationsAreValidated() {
        var generated = RagSettings.from(Map.of("RAG_DB", settings.db(), "RAG_CHAT_MODEL", "fake"));
        rag = new RagService(generated, store, models);
        rag.ingest("知识库支持文本型 PDF。", "格式", "files.md", "default");
        assertEquals("generated", rag.ask("知识库", "default", 5).get("mode"));
        for (String answer : List.of("错误引用 [99]", "没有引用", "巨大编号 [99999999999999999999999]", RagService.REFUSAL)) {
            models.response = answer;
            assertEquals("refused", rag.ask("知识库", "default", 5).get("mode"));
        }
    }
    @Test void vectorRecallAndIndexSignatureGuard() {
        settings = RagSettings.from(Map.of("RAG_DB", temp.resolve("vector.db").toString(), "RAG_EMBED_MODEL", "fake"));
        store = new RagStore(settings); rag = new RagService(settings, store, models);
        rag.ingest("汽车行驶要求。", "车辆规范", "car.md", "default");
        assertEquals("car.md", rag.search("机动车", "default", 3).getFirst().get("source"));
        var changed = RagSettings.from(Map.of("RAG_DB", settings.db(), "RAG_EMBED_MODEL", "another"));
        assertThrows(IllegalArgumentException.class, () -> new RagStore(changed));
    }
    @Test void failedEmbeddingPreservesOldVersion() {
        rag = new RagService(settings, store, models);
        var first = rag.ingest("苹果采购标准。", "制度", "a.md", "default");
        models.failEmbedding = true;
        assertThrows(ModelException.class, () -> rag.ingest("香蕉采购标准。", "制度", "a.md", "default"));
        assertEquals("苹果采购标准。", store.document((String) first.get("document_id")).get("text"));
    }
    @Test void legacyPythonSignatureFormattingAndIds() {
        store.read(db -> RagStore.update(db, "UPDATE metadata SET value=? WHERE key='index_signature'", "[\"\", \"\", 800, 120]"));
        var first = rag.ingest("😀知识库", "格式", "files.md", "default");
        var old = store.document((String) first.get("document_id"));
        var reopened = new RagStore(settings);
        assertEquals(old, reopened.document((String) first.get("document_id")));
        assertEquals(TextProcessing.hash("default\0files.md").substring(0,32), first.get("document_id"));
    }
    @Test void failedGenerationLeavesTrace() {
        var generated = RagSettings.from(Map.of("RAG_DB", settings.db(), "RAG_CHAT_MODEL", "fake"));
        rag = new RagService(generated, store, models);
        rag.ingest("苹果采购标准。", "制度", "a.md", "default");
        models.failAnswer = true;
        assertThrows(ModelException.class, () -> rag.ask("苹果采购", "default", 3));
        var rows = store.read(db -> RagStore.query(db, "SELECT data FROM traces"));
        assertEquals("error", Json.read((String) rows.getFirst().get("data")).get("status").asText());
    }
    @Test void validationRejectsEmptyAndOutOfRangeInputs() {
        assertThrows(IllegalArgumentException.class, () -> rag.ask("  ", "default", 3));
        assertThrows(IllegalArgumentException.class, () -> rag.ask("问题", "default", 21));
        assertThrows(IllegalArgumentException.class, () -> rag.ingest("", "标题", "a.md", "default"));
        assertThrows(IllegalArgumentException.class, () -> rag.ask("问题", "../private", 3));
    }
}
