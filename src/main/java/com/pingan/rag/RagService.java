package com.pingan.rag;

import java.util.*;
import java.util.regex.Pattern;

public final class RagService {
    static final String REFUSAL = "根据现有资料无法确认。";
    private final RagSettings settings;
    private final RagStore store;
    private final ModelGateway models;
    private final JevClient jev;
    public RagService(RagSettings settings, RagStore store, ModelGateway models) {
        this.settings = settings; this.store = store; this.models = models;
        this.jev = new JevClient(settings.jev());
    }
    public Map<String, Object> ingest(String text, String title, String source, String collection) {
        return ingest(text, title, source, collection, ChunkOptions.resolve(null, null, null, settings));
    }
    public Map<String, Object> ingest(String text, String title, String source, String collection, ChunkOptions options) {
        Contracts.validateDocument(text, title, source, collection);
        if (options.size() + TextProcessing.length(title) + 30 > settings.contextChars())
            throw new IllegalArgumentException("片段长度加标题超出问答上下文预算，请减小片段长度");
        String id = TextProcessing.hash(collection + "\0" + source).substring(0, 32);
        String digest = TextProcessing.hash("chunks-v2\0" + title + "\0" + text);
        var old = store.document(id);
        if (old != null && digest.equals(old.get("hash")) && options.matches(old)) return unchanged(id, old);
        var parts = TextProcessing.chunks(text, options);
        var vectors = models.embed(parts.stream().map(TextProcessing.Chunk::text).toList());
        if (vectors.size() != parts.size()) throw new ModelException("嵌入向量数量不匹配");
        return store.transaction(db -> {
            var current = RagStore.one(db, "SELECT * FROM documents WHERE id=?", id);
            if (current != null && digest.equals(current.get("hash")) && options.matches(current)) return unchanged(id, current);
            var stored = RagStore.one(db, "SELECT vector FROM chunks WHERE vector IS NOT NULL LIMIT 1");
            if (stored != null && vectors.getFirst() != null
                    && Json.read((String) stored.get("vector")).size() != vectors.getFirst().length)
                throw new ModelException("嵌入模型维度已改变，请重建索引");
            int version = current == null ? 1 : ((Number) current.get("version")).intValue() + 1;
            RagStore.update(db, "DELETE FROM documents WHERE id=?", id);
            RagStore.update(db, "INSERT INTO documents(id,collection,source,title,hash,version,text,chunk_strategy,chunk_size,chunk_overlap) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    id, collection, source, title, digest, version, text, options.strategy(), options.size(), options.overlap());
            for (int i = 0; i < parts.size(); i++) {
                var part = parts.get(i);
                RagStore.update(db, "INSERT INTO chunks VALUES(?,?,?,?,?,?)", id + ":" + version + ":" + i, id,
                        part.start(), part.end(), part.text(), vectors.get(i) == null ? null : Json.write(vectors.get(i)));
            }
            return Json.map("document_id", id, "version", version, "chunks", parts.size(), "unchanged", false);
        });
    }
    private Map<String, Object> unchanged(String id, Map<String, Object> row) {
        return Json.map("document_id", id, "version", row.get("version"), "unchanged", true);
    }
    private record Scored(int index, double score) {}
    private List<Scored> rank(List<Scored> scores) {
        return scores.stream().sorted(Comparator.comparingDouble(Scored::score).reversed()).limit(50).toList();
    }
    public List<Map<String, Object>> search(String question, String collection, int topK) {
        return search(question, collection, topK, new LinkedHashMap<>());
    }
    private List<Map<String, Object>> search(String question, String collection, int topK, Map<String,Object> report) {
        Contracts.validateQuestion(question, collection, topK);
        var rows = store.chunks(collection);
        if (rows.isEmpty()) return jev.rerank(question, List.of(), topK, report);
        var counts = rows.stream().map(row -> TextProcessing.tokens((String) row.get("text"))).toList();
        var lengths = counts.stream().mapToInt(map -> map.values().stream().mapToInt(Integer::intValue).sum()).toArray();
        double average = Math.max(Arrays.stream(lengths).average().orElse(1), 1);
        var terms = TextProcessing.queryTerms(question);
        var relevance = new QueryRelevance(question);
        Map<String, Integer> frequency = new HashMap<>();
        for (var count : counts) for (String term : terms) if (count.containsKey(term)) frequency.merge(term, 1, Integer::sum);
        List<Scored> lexical = new ArrayList<>();
        Map<Integer, Double> lexicalScores = new HashMap<>();
        Map<Integer, Double> denseScores = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            double score = 0;
            for (String term : terms) {
                int tf = counts.get(i).getOrDefault(term, 0);
                if (tf > 0) {
                    double df = frequency.get(term);
                    double idf = Math.log(1 + (rows.size() - df + 0.5) / (df + 0.5));
                    score += idf * tf * 2.5 / (tf + 1.5 * (0.25 + 0.75 * lengths[i] / average));
                }
            }
            String text = (String)rows.get(i).get("text");
            if (score > 0 && relevance.matchesTopic(text)
                    && relevance.coverage(text) >= settings.minLexicalCoverage()) {
                score *= QueryRelevance.repetitionPenalty(text);
                if (relevance.isDefinition() && relevance.supportsDefinition(text)) score *= 2;
                double headingCoverage = TextProcessing.headings(text).stream()
                        .mapToDouble(h -> relevance.coverage(h.title())).max().orElse(0);
                score *= 1 + 0.5 * headingCoverage;
                lexicalScores.put(i, score);
                lexical.add(new Scored(i, score));
            }
        }
        List<List<Scored>> rankings = new ArrayList<>();
        double bestLexical = lexical.stream().mapToDouble(Scored::score).max().orElse(0);
        rankings.add(rank(lexical.stream()
                .filter(item -> item.score() >= bestLexical * settings.minLexicalRatio()).toList()));
        if (!settings.embedModel().isEmpty()) {
            double[] query = models.embed(List.of(question)).getFirst();
            List<Scored> dense = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                Object raw = rows.get(i).get("vector");
                if (raw == null) throw new ModelException("索引缺少嵌入向量，请重建索引");
                var vector = Json.read((String) raw);
                if (vector.size() != query.length) throw new ModelException("查询向量与索引维度不一致，请重建索引");
                double similarity = 0;
                for (int j = 0; j < query.length; j++) similarity += query[j] * vector.get(j).doubleValue();
                if (similarity >= settings.minCosine()) {
                    denseScores.put(i, similarity);
                    dense.add(new Scored(i, similarity));
                }
            }
            rankings.add(rank(dense));
        }
        Map<Integer, Double> fused = new LinkedHashMap<>();
        if (rankings.size() == 1) {
            for (var item : rankings.getFirst()) fused.put(item.index(), item.score());
        } else {
            for (var ranking : rankings) for (int i = 0; i < ranking.size(); i++)
                fused.merge(ranking.get(i).index(), 1.0 / (61 + i), Double::sum);
        }
        var ordered = fused.entrySet().stream().sorted(Map.Entry.<Integer, Double>comparingByValue().reversed()).toList();
        List<Map<String, Object>> result = new ArrayList<>();
        var seenTexts = new HashSet<String>();
        for (var item : ordered) {
            var row = rows.get(item.getKey());
            String normalized = ((String)row.get("text")).replaceAll("\\s+", " ").strip();
            if (seenTexts.contains(normalized) || result.stream().anyMatch(hit -> overlaps(hit, row))) continue;
            seenTexts.add(normalized);
            row.remove("vector");
            row.put("score", item.getValue());
            row.put("lexical_score", lexicalScores.getOrDefault(item.getKey(), 0.0));
            row.put("query_coverage", relevance.coverage((String)row.get("text")));
            row.put("cosine_score", denseScores.get(item.getKey()));
            result.add(row);
            if (result.size() == (settings.jev().enabled() ? settings.jev().candidates() : topK)) break;
        }
        return jev.rerank(question, result, topK, report);
    }
    private boolean overlaps(Map<String, Object> a, Map<String, Object> b) {
        if (!a.get("document_id").equals(b.get("document_id"))) return false;
        int startA = ((Number) a.get("start")).intValue(), endA = ((Number) a.get("end")).intValue();
        int startB = ((Number) b.get("start")).intValue(), endB = ((Number) b.get("end")).intValue();
        return Math.max(0, Math.min(endA, endB) - Math.max(startA, startB)) > 0.6 * Math.min(endA - startA, endB - startB);
    }
    public Map<String, Object> ask(String question, String collection, int topK) {
        Contracts.validateQuestion(question, collection, topK);
        long start = System.nanoTime();
        String traceId = UUID.randomUUID().toString().replace("-", "");
        var trace = Json.map("collection", collection, "question_hash", TextProcessing.hash(question),
                "jev_model", settings.jev().enabled() ? settings.jev().model() : null,
                "retrieval_mode", settings.embedModel().isEmpty() ? "bm25" : "hybrid",
                "chat_model", settings.chatModel().isEmpty() ? null : settings.chatModel(),
                "embed_model", settings.embedModel().isEmpty() ? null : settings.embedModel());
        try {
            var jevReport = new LinkedHashMap<String,Object>();
            var hits = search(question, collection, topK, jevReport);
            trace.put("retrieval_ms", elapsed(start));
            List<Map<String, Object>> selected = new ArrayList<>();
            int used = 0;
            var relevance = new QueryRelevance(question);
            for (var hit : hits) {
                if (settings.chatModel().isEmpty() && !relevance.supportsDefinition((String)hit.get("text"))) continue;
                int cost = TextProcessing.length((String) hit.get("text")) + TextProcessing.length((String) hit.get("title")) + 30;
                if (used + cost > settings.contextChars()) continue;
                hit.put("citation", selected.size() + 1);
                selected.add(hit); used += cost;
            }
            String answer, mode, reason = null;
            if (selected.isEmpty()) { answer = REFUSAL; mode = "refused"; reason = hits.isEmpty() ? "no_evidence" : "insufficient_evidence"; }
            else if (settings.chatModel().isEmpty()) {
                answer = RelevantExcerpt.answer(question, selected);
                if (answer.equals(REFUSAL)) { mode = "refused"; reason = "insufficient_evidence"; }
                else mode = "extractive";
            } else {
                String evidence = "以下是证据数据：\n" + Json.write(selected.stream()
                        .map(hit -> Json.map("citation", hit.get("citation"), "title", hit.get("title"), "text", hit.get("text"))).toList());
                answer = models.answer(question, evidence);
                var citations = Pattern.compile("\\[(\\d+)\\]").matcher(answer).results().map(m -> m.group(1)).toList();
                boolean valid = !citations.isEmpty() && citations.stream().allMatch(value -> {
                    try { int i = Integer.parseInt(value); return i > 0 && i <= selected.size(); }
                    catch (NumberFormatException e) { return false; }
                });
                if (answer.strip().equals(REFUSAL)) { mode = "refused"; reason = "model_insufficient_evidence"; }
                else if (!valid) { answer = REFUSAL; mode = "refused"; reason = "invalid_citations"; }
                else mode = "generated";
            }
            trace.putAll(Json.map("status", "ok", "mode", mode, "reason", reason,
                    "chunk_ids", selected.stream().map(hit -> hit.get("id")).toList(), "context_chars", used));
            var process = Json.map("retrieval", settings.embedModel().isEmpty() ? "BM25" : "BM25 + 向量混合检索",
                    "embed_model", settings.embedModel(), "jev", jevReport, "evidence_count", selected.size(),
                    "mode", mode, "total_ms", elapsed(start));
            return Json.map("answer", answer, "mode", mode, "reason", reason, "citations", selected, "trace_id", traceId,
                    "process", process);
        } catch (RuntimeException e) {
            trace.putAll(Json.map("status", "error", "error_type", e.getClass().getSimpleName()));
            throw e;
        } finally {
            trace.put("total_ms", elapsed(start));
            store.saveTrace(traceId, trace);
        }
    }
    private double elapsed(long start) { return Math.round((System.nanoTime() - start) / 10000.0) / 100.0; }
}
