package com.pingan.rag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class RelevantExcerptTest {
 @TempDir Path temp;
 @Test void keywordMentionIsNotDefinitionAndTraceRecordsRefusal() {
  var settings=RagSettings.from(Map.of("RAG_DB",temp.resolve("mentions.db").toString()));
  var store=new RagStore(settings);
  var rag=new RagService(settings,store,new CompatibleModelClient(settings));
  rag.ingest("## 课程安排\n| 第 2 期 | RAG 深度优化 | 混合检索、引用溯源 |\n\n下期将升级为深度 RAG。",
      "课程","course.md","default");
  assertFalse(rag.search("什么是 RAG？","default",5).isEmpty());
  for (String question : new String[]{"什么是 RAG？", "RAG 是什么？", "什么是 RAG？它适合解决哪些问题？"}) {
   var result=rag.ask(question,"default",5);
   assertEquals("refused",result.get("mode"));
   assertEquals(RagService.REFUSAL,result.get("answer"));
   assertEquals("insufficient_evidence",result.get("reason"));
   assertEquals("insufficient_evidence",store.trace((String)result.get("trace_id")).get("reason"));
  }
 }
 @Test void definitionInLongPlainDocumentReturnsOnlyExplanation() {
  String text="课程安排和其他说明。".repeat(20)+"RAG 是检索增强生成。"+"无关的课程日程。".repeat(20);
  assertEquals("[2] RAG 是检索增强生成。",RelevantExcerpt.answer("什么是 RAG？",
      java.util.List.of(Json.map("text",text,"citation",2))));
  assertEquals("[1] 检索增强生成是将检索结果作为生成依据的方法。",RelevantExcerpt.answer("什么是检索增强生成？",
      java.util.List.of(Json.map("text","检索增强生成是将检索结果作为生成依据的方法。","citation",1))));
 }
 @Test void bodyDefinitionBeatsHeadingOnlyMatchAndAbsentTopicIsRejected() {
  var hits=java.util.List.of(Json.map("text","## RAG\n下期进行培训。","citation",1),
      Json.map("text","技术说明。\n\nRAG（检索增强生成）是结合检索与生成的方法。","citation",2));
  assertEquals("[2] RAG（检索增强生成）是结合检索与生成的方法。",RelevantExcerpt.answer("什么是 RAG？",hits));
  assertEquals(RagService.REFUSAL,RelevantExcerpt.answer("采购预算",hits));
 }
 @Test void namedTopicIsRequiredForLexicalRecallAndDomainWordsArePreserved() {
  var settings=RagSettings.from(Map.of("RAG_DB",temp.resolve("named.db").toString()));
  var rag=new RagService(settings,new RagStore(settings),new CompatibleModelClient(settings));
  rag.ingest("## 成本\n采购成本预算需要审批。","采购","purchase.md","default");
  assertTrue(rag.search("RAG 成本","default",3).isEmpty());
  assertTrue(TextProcessing.queryTerms("问题排查").contains("问题"));
  assertTrue(TextProcessing.queryTerms("解决方案").contains("解决"));
 }
 @Test void genericQuestionWordsMustNotPromoteUnrelatedHeading() {
  var settings=RagSettings.from(Map.of("RAG_DB",temp.resolve("topic.db").toString()));
  var rag=new RagService(settings,new RagStore(settings),new CompatibleModelClient(settings));
  rag.ingest("### 开讲：本模块解决什么问题\n本期介绍 LangChain 与 LangGraph 的工程实践。\n\n"
      + "### RAG 定义与应用\nRAG 是检索增强生成。适用于内部知识问答、文档查询。\n",
      "课程","course.md","default");
  var question="什么是 RAG？它适合解决哪些问题？";
  var answer=(String)rag.ask(question,"default",3).get("answer");
  assertTrue(answer.contains("RAG 是检索增强生成"));
  assertFalse(answer.contains("LangGraph"));
  assertEquals(0,RelevantExcerpt.headingScore(question,"### 本模块解决什么问题\n无关内容"));
 }
 @Test void unrelatedCourseCannotAnswerRagQuestion() {
  var settings=RagSettings.from(Map.of("RAG_DB",temp.resolve("absent.db").toString()));
  var rag=new RagService(settings,new RagStore(settings),new CompatibleModelClient(settings));
  rag.ingest("### 本模块解决什么问题\n本期学习工作流编排。","课程","course.md","default");
  assertEquals("refused",rag.ask("什么是 RAG？它适合解决哪些问题？","default",3).get("mode"));
 }
 @Test void trainingGoalsPreferLearningHeadingAndExcludeOtherSections() {
  var settings=RagSettings.from(Map.of("RAG_DB",temp.resolve("test.db").toString()));
  var rag=new RagService(settings,new RagStore(settings),new CompatibleModelClient(settings));
  rag.ingest("# 课程介绍\n培训日程。\n\n### 学习目标\n1. 理解状态契约。\n2. 掌握中断恢复。\n\n### 本日产出\n提交作业。","课程","course.md","default");
  rag.ingest("培训目标路由目标培训目标路由目标。".repeat(30),"噪声","noise.md","default");
  var result=rag.ask("培训目标","default",3);
  var answer=(String)result.get("answer");
  assertTrue(answer.contains("学习目标")); assertTrue(answer.contains("掌握中断恢复"));
  assertFalse(answer.contains("提交作业")); assertFalse(answer.contains("路由目标"));
 }
 @Test void noHeadingUsesRelevantParagraphAndCapsLongText() {
  var hits=java.util.List.of(Json.map("text","无关内容。\n\n费用上限为 200 元。\n\n其他说明。","citation",1));
  assertEquals("[1] 费用上限为 200 元。",RelevantExcerpt.answer("费用上限",hits));
  var longHits=java.util.List.of(Json.map("text","费用".repeat(900),"citation",1));
  assertTrue(RelevantExcerpt.answer("费用",longHits).contains("摘录已截断"));
 }
}
