package com.pingan.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SearchNoiseTest {
    @TempDir Path temp;
    private RagService service(Map<String,String> options) {
        var config = new HashMap<>(options);
        config.put("RAG_DB", temp.resolve(UUID.randomUUID() + ".db").toString());
        var settings = RagSettings.from(config);
        return new RagService(settings, new RagStore(settings), new CompatibleModelClient(settings));
    }

    @Test void topKDoesNotFillWithSharedWordNoise() {
        var rag = service(Map.of());
        rag.ingest("苹果采购预算规定：每箱十二元。", "规定", "correct.md", "default");
        rag.ingest("采购流程需要审批。", "流程", "noise.md", "default");
        var hits = rag.search("苹果采购预算", "default", 10);
        assertEquals(1, hits.size());
        assertEquals("correct.md", hits.getFirst().get("source"));
        assertEquals(1.0, hits.getFirst().get("query_coverage"));
        assertEquals(hits.getFirst().get("score"), hits.getFirst().get("lexical_score"));
    }

    @Test void equivalentCourseTermsKeepGoalsButRejectOtherTargets() {
        var rag = service(Map.of());
        rag.ingest("## 学习目标\n掌握状态契约与中断恢复。", "课程", "goals.md", "default");
        rag.ingest("## 路由规则\n目标节点由条件决定。", "路由", "routing.md", "default");
        var hits = rag.search("培训目标", "default", 10);
        assertEquals(1, hits.size());
        assertEquals("goals.md", hits.getFirst().get("source"));
    }

    @Test void relativeScoreRemovesIncidentalMention() {
        var rag = service(Map.of("RAG_MIN_LEXICAL_RATIO", "0.6"));
        rag.ingest("采购：每箱十二元。", "规定", "correct.md", "default");
        rag.ingest("采购。" + "例行系统运行日志。".repeat(55), "日志", "noise.md", "default");
        var hits = rag.search("采购", "default", 10);
        assertEquals(1, hits.size());
        assertEquals("correct.md", hits.getFirst().get("source"));
    }

    @Test void exactDuplicatesCollapseButDifferentNumbersRemain() {
        var rag = service(Map.of());
        rag.ingest("苹果采购预算每箱十二元。", "规定", "a.md", "default");
        rag.ingest("苹果采购预算每箱十二元。", "副本", "b.md", "default");
        rag.ingest("苹果采购预算每箱二十元。", "另一个规定", "c.md", "default");
        assertEquals(2, rag.search("苹果采购预算", "default", 10).size());
    }

    @Test void definitionMentionsDoNotBecomeAnswerCitations() {
        var rag = service(Map.of());
        rag.ingest("第 2 期将介绍 RAG。", "课表", "schedule.md", "default");
        var result = rag.ask("什么是 RAG？", "default", 5);
        assertEquals("refused", result.get("mode"));
        assertEquals("insufficient_evidence", result.get("reason"));
        assertTrue(((List<?>)result.get("citations")).isEmpty());
        rag.ingest("RAG 是检索增强生成。", "定义", "definition.md", "default");
        result = rag.ask("什么是 RAG？", "default", 5);
        assertEquals("extractive", result.get("mode"));
        assertEquals(1, ((List<?>)result.get("citations")).size());
    }

    @Test void thresholdsValidateAndDoNotChangeIndexSignature() {
        var defaults = RagSettings.from(Map.of());
        var adjusted = RagSettings.from(Map.of("RAG_MIN_LEXICAL_COVERAGE", "0.3", "RAG_MIN_LEXICAL_RATIO", "0.7"));
        assertEquals(defaults.signature(), adjusted.signature());
        for (String key : List.of("RAG_MIN_LEXICAL_COVERAGE", "RAG_MIN_LEXICAL_RATIO")) {
            for (String value : List.of("-1", "1.1", "NaN")) {
                assertThrows(IllegalArgumentException.class, () -> RagSettings.from(Map.of(key, value)));
            }
        }
    }
}
