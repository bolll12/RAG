package com.pingan.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ChunkOptionsTest {
    @TempDir Path temp;
    @Test void codeCommentsAreNotSectionHeadings() {
        String text = "# 代码示例\n```python\n# RAG 注释\nprint('ok')\n```\n## 总结\n完成。";
        assertEquals(2,TextProcessing.headings(text).size());
        assertEquals(2,TextProcessing.chunks(text,new ChunkOptions("paragraph",100,10)).size());
        assertEquals(0,RelevantExcerpt.headingScore("RAG",text));
    }
    @Test void structuralChunksNeverCrossHeadingsAndKeepOffsets() {
        String input = "# 课程😀\n介绍。\n\n## RAG\n检索增强生成。\n\n## 工作流\n流程编排。\n";
        for (String strategy : new String[]{"paragraph", "sentence"}) {
            var chunks = TextProcessing.chunks(input,new ChunkOptions(strategy,100,20));
            assertEquals(3,chunks.size());
            assertEquals(input,chunks.stream().map(TextProcessing.Chunk::text).collect(java.util.stream.Collectors.joining()));
            assertTrue(chunks.get(1).text().startsWith("## RAG"));
            assertFalse(chunks.get(1).text().contains("工作流"));
            for (var chunk : chunks) assertEquals(chunk.text(),TextProcessing.slice(input,chunk.start(),chunk.end()));
        }
        assertEquals(1,TextProcessing.chunks(input,new ChunkOptions("fixed",100,20)).size());
    }
    @Test void overlapStartsAtSentenceBoundary() {
        String sentence = "甲".repeat(29) + "。";
        var chunks = TextProcessing.chunks(sentence.repeat(9),new ChunkOptions("sentence",100,35));
        assertTrue(chunks.size()>1);
        for (var chunk : chunks) assertEquals(0,chunk.start()%30);
        assertEquals(60,chunks.get(1).start());
    }
    @Test void strategiesHaveDifferentBoundariesAndPreserveCodePoints() {
        String input = "甲".repeat(65) + "！" + "乙".repeat(13) + "\n" + "丙".repeat(150) + "😀";
        assertEquals(100, TextProcessing.chunks(input, new ChunkOptions("fixed",100,10)).getFirst().end());
        assertEquals(80, TextProcessing.chunks(input, new ChunkOptions("paragraph",100,10)).getFirst().end());
        assertEquals(66, TextProcessing.chunks(input, new ChunkOptions("sentence",100,10)).getFirst().end());
        for (String strategy : new String[]{"fixed", "paragraph", "sentence"}) {
            boolean[] coverage = new boolean[TextProcessing.length(input)];
            for (var chunk : TextProcessing.chunks(input, new ChunkOptions(strategy,100,10))) {
                assertEquals(chunk.text(),TextProcessing.slice(input,chunk.start(),chunk.end()));
                assertTrue(TextProcessing.length(chunk.text())<=100);
                for(int i=chunk.start();i<chunk.end();i++) coverage[i]=true;
            }
            for(boolean covered:coverage) assertTrue(covered);
        }
    }
    @Test void configChangesReindexSameDocumentAndPersist() {
        var settings=RagSettings.from(Map.of("RAG_DB",temp.resolve("rag.db").toString()));
        var store=new RagStore(settings);
        var service=new RagService(settings,store,new CompatibleModelClient(settings));
        String body="知识库说明。".repeat(100);
        var first=service.ingest(body,"测试","a.md","default",new ChunkOptions("fixed",100,10));
        assertEquals(true,service.ingest(body,"测试","a.md","default",new ChunkOptions("fixed",100,10)).get("unchanged"));
        var second=service.ingest(body,"测试","a.md","default",new ChunkOptions("sentence",200,20));
        assertEquals(first.get("document_id"),second.get("document_id"));
        assertEquals(2,second.get("version"));
        var row=new RagStore(settings).document((String)first.get("document_id"));
        assertEquals("sentence",row.get("chunk_strategy"));assertEquals(200,row.get("chunk_size"));
        assertTrue(store.chunks("default").stream().allMatch(c->((String)c.get("id")).contains(":2:")));
    }
    @Test void invalidOptionsAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new ChunkOptions("semantic",800,120));
        assertThrows(IllegalArgumentException.class,()->new ChunkOptions("fixed",100,100));
        assertThrows(IllegalArgumentException.class,()->new ChunkOptions("fixed",99,0));
    }
}
