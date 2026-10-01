package com.pingan.rag;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class WikiServiceTest {
    @TempDir Path dir;
    RagStore store;
    RagService rag;
    WikiService wiki;
    AtomicInteger calls = new AtomicInteger();
    String quote = "RAG 通过检索企业文档，为模型生成回答提供可靠的上下文。";

    @BeforeEach void setup() {
        var settings = RagSettings.from(Map.of("RAG_DB", dir.resolve("wiki.db").toString()));
        store = new RagStore(settings);
        rag = new RagService(settings, store, new CompatibleModelClient(settings));
        configure(false, (system, input) -> {
            calls.incrementAndGet();
            if (Json.read(input).has("question")) return Json.write(Json.map("claims", List.of(Json.map("text", "RAG 使用检索到的文档辅助生成回答。", "citations", List.of(1)))));
            StringBuilder source = new StringBuilder();
            for (var passage : Json.read(input).path("passages")) source.append(passage.path("text").asText());
            String raw = source.toString();
            return fragment("RAG", "RAG 使用文档提供上下文。", raw.contains(quote) ? quote : raw);
        });
    }
    void configure(boolean auto, WikiModel model) {
        if (wiki != null) wiki.close();
        wiki = new WikiService(store, WikiSettings.from(Map.of("RAG_WIKI_MODEL", "remote-test", "RAG_WIKI_AUTO_UPDATE", String.valueOf(auto))), model);
    }
    @AfterEach void close() { wiki.close(); }
    static String fragment(String title, String text, String quote) {
        return Json.write(Json.map("topics", List.of(Json.map("title", title, "claims", List.of(Json.map("text", text, "quote", quote)), "links", List.of("检索")))));
    }
    String ingest(String text, String source, String collection) { return (String) rag.ingest(text, source, source, collection).get("document_id"); }
    Map<String, Object> await(Map<String, Object> job) throws Exception {
        for (int i = 0; i < 300; i++) {
            var status = new WikiStore(store).job((String) job.get("id"));
            if (!List.of("running", "queued").contains(status.get("status"))) return status;
            Thread.sleep(10);
        }
        throw new AssertionError("Wiki job did not finish");
    }
    void build(String collection) throws Exception { assertEquals("completed", await(wiki.build(collection)).get("status")); }
    String topic(String collection) { return (String) wiki.pages(collection, "").stream().filter(p -> p.get("kind").equals("topic")).findFirst().orElseThrow().get("id"); }

    @Test void sourceTopicsLinksExactUnicodeCitationsAndIsolation() throws Exception {
        String raw = "😀文档前言\n\n" + quote;
        String document = ingest(raw, "入门.md", "one");
        ingest(quote, "独立.md", "two");
        build("one");
        assertEquals(2, wiki.pages("one", "").size());
        assertTrue(wiki.pages("two", "").isEmpty());
        String id = topic("one");
        var page = wiki.page(id, "one");
        var content = Json.MAPPER.valueToTree(page.get("content"));
        var citation = content.path("facts").get(0).path("citations").get(0);
        assertEquals(document, citation.path("document_id").asText());
        assertEquals(quote, TextProcessing.slice(raw, citation.path("start").asInt(), citation.path("end").asInt()));
        assertFalse(((List<?>) page.get("links")).isEmpty());
        assertFalse(((List<?>) page.get("backlinks")).isEmpty());
        assertThrows(ResponseStatusException.class, () -> wiki.page(id, "two"));
        assertTrue(wiki.markdown(id, "one").contains(quote));
        assertTrue(Json.write(wiki.lint("one")).contains("missing_topic"));
    }
    @Test void cacheReuseUnchangedBuildAndCrossDocumentEvidenceMerge() throws Exception {
        ingest(quote, "A.md", "default"); ingest(quote, "B.md", "default");
        build("default");
        assertEquals(1, calls.get());
        var page = wiki.page(topic("default"), "default");
        assertEquals(2, Json.MAPPER.valueToTree(page.get("content")).path("facts").get(0).path("citations").size());
        build("default");
        assertEquals(1, calls.get());
        assertEquals(1, wiki.revisions(topic("default"), "default").size());
    }
    @Test void editsAndDeletionInvalidatePagesAndPreserveRevisionHistory() throws Exception {
        String id = ingest(quote, "A.md", "default"); build("default");
        String pageId = topic("default");
        ingest("RAG 更新后的制度规定必须记录检索来源。", "A.md", "default");
        assertEquals("stale", wiki.page(pageId, "default").get("status"));
        assertTrue(((List<?>) wiki.ask("RAG 是什么", "default", false).get("facts")).isEmpty());
        build("default");
        assertEquals(2, wiki.revisions(pageId, "default").size());
        assertEquals("ready", wiki.page(pageId, "default").get("status"));
        assertTrue(store.delete(id)); build("default");
        assertTrue(wiki.pages("default", "").isEmpty());
        assertThrows(ResponseStatusException.class, () -> wiki.page(pageId, "default"));
    }
    @Test void fabricatedQuotationFailsWithoutPublishingPartialPages() throws Exception {
        ingest(quote, "A.md", "default"); build("default");
        String pageId = topic("default");
        ingest("企业的新制度要求记录查询问题。", "A.md", "default");
        configure(false, (s, i) -> fragment("假的主题", "虚构事实", "原文不存在的引文"));
        var job = await(wiki.build("default"));
        assertEquals("failed", job.get("status"));
        assertTrue(job.get("message").toString().contains("引用校验"));
        assertEquals("stale", wiki.page(pageId, "default").get("status"));
        assertTrue(wiki.pages("default", "假的主题").isEmpty());
    }
    @Test void sourceChangeDuringGenerationCannotPublishObsoleteResult() throws Exception {
        ingest(quote, "A.md", "default");
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        configure(false, (s, i) -> {
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new RuntimeException(e); }
            return fragment("RAG", "旧知识", quote);
        });
        var job = wiki.build("default");
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(job.get("id"), wiki.build("default").get("id"));
            ingest("RAG 新来源要求检索质量评估。", "A.md", "default");
        } finally { release.countDown(); }
        assertEquals("superseded", await(job).get("status"));
        assertTrue(wiki.pages("default", "").isEmpty());
    }
    @Test void wikiAnswerCanBeSavedWithOriginalSourcesAndBecomesStaleAfterUpdate() throws Exception {
        String document = ingest(quote, "A.md", "default"); build("default");
        var answer = wiki.ask("RAG 是什么", "default", true);
        String id = (String) answer.get("page_id"); assertNotNull(id);
        assertEquals("answer", wiki.page(id, "default").get("kind"));
        assertTrue(Json.write(answer).contains(document));
        ingest("RAG 是企业资料检索增强生成的一种方案。", "A.md", "default"); build("default");
        assertEquals("stale", wiki.page(id, "default").get("status"));
    }
    @Test void invalidAnswerCitationCannotBeSaved() throws Exception {
        ingest(quote, "A.md", "default"); build("default");
        configure(false, (s, i) -> "{\"claims\":[{\"text\":\"错误引用\",\"citations\":[99]}]}");
        assertThrows(ModelException.class, () -> wiki.ask("RAG 是什么", "default", true));
        assertEquals(2, wiki.pages("default", "").size());
    }
    @Test void sourceChangesQueueAutomaticBuilds() throws Exception {
        configure(true, (s, i) -> fragment("RAG", "RAG 使用文档提供上下文。", quote));
        ingest(quote, "A.md", "auto");
        var job = Json.object(Json.write(wiki.status("auto").get("job")));
        assertEquals("completed", await(job).get("status"));
        assertEquals(2, wiki.pages("auto", "").size());
    }
    @Test void unavailableModelDoesNotBreakDocumentUploads() {
        wiki.close(); wiki = new WikiService(store, WikiSettings.from(Map.of()), (s, i) -> { throw new AssertionError(); });
        ingest(quote, "A.md", "default");
        assertFalse((Boolean) wiki.status("default").get("enabled"));
        assertEquals(503, assertThrows(ResponseStatusException.class, () -> wiki.build("default")).getStatusCode().value());
        assertEquals(1, store.documents("default").size());
    }
    @Test void passageIdsCopyExactOriginalTextAndRejectInvalidIds() throws Exception {
        ingest(quote, "A.md", "default");
        configure(false, (s, i) -> "{\"topics\":[{\"title\":\"RAG\",\"claims\":[{\"text\":\"RAG 提供上下文\",\"passage\":1}],\"links\":[]}]}");
        build("default");
        var page = wiki.page(topic("default"), "default");
        assertEquals(quote, Json.MAPPER.valueToTree(page.get("content")).path("facts").get(0).path("citations").get(0).path("quote").asText());
        ingest("更新后的 RAG 文档资料。", "A.md", "default");
        configure(false, (s, i) -> "{\"topics\":[{\"title\":\"RAG\",\"claims\":[{\"text\":\"无效知识\",\"passage\":99}],\"links\":[]}]}");
        assertEquals("failed", await(wiki.build("default")).get("status"));
        assertEquals("stale", wiki.page(topic("default"), "default").get("status"));
    }
    @Test void repeatedPassagesRetainTheSelectedOccurrenceOffset() throws Exception {
        String block = "## 概念\n" + quote + "\n\n";
        ingest(block + block, "A.md", "default");
        configure(false, (s, i) -> "{\"topics\":[{\"title\":\"RAG\",\"claims\":[{\"text\":\"RAG 的含义\",\"passage\":2}],\"links\":[]}]}");
        build("default");
        var content = Json.MAPPER.valueToTree(wiki.page(topic("default"), "default").get("content"));
        assertEquals(TextProcessing.length(block), content.path("facts").get(0).path("citations").get(0).path("start").asInt());
    }
    @Test void interruptedJobsBecomeRetryableAfterRestart() {
        var job = new WikiStore(store).newJob("default");
        configure(false, (s, i) -> { throw new AssertionError(); });
        assertEquals("failed", new WikiStore(store).job((String) job.get("id")).get("status"));
    }
}
