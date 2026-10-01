package com.pingan.rag;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record RagSettings(String db, String apiKey, String chatBaseUrl, String chatModel,
                          String chatApiKey, String embedBaseUrl, String embedModel,
                          String embedApiKey, double timeout, int chunkSize, int overlap,
                          int contextChars, double minCosine, double minLexicalCoverage, double minLexicalRatio, JevSettings jev) {
    public RagSettings {
        if (chunkSize < 100 || overlap < 0 || overlap >= chunkSize)
            throw new IllegalArgumentException("切片参数须满足 0 <= overlap < chunk_size，chunk_size >= 100");
        if (contextChars < chunkSize || !Double.isFinite(timeout) || timeout <= 0)
            throw new IllegalArgumentException("上下文预算不能小于切片大小，超时必须大于零");
        if (!Double.isFinite(minCosine) || minCosine < -1 || minCosine > 1)
            throw new IllegalArgumentException("RAG_MIN_COSINE 必须介于 -1 和 1");
        if (!Double.isFinite(minLexicalCoverage) || minLexicalCoverage < 0 || minLexicalCoverage > 1
                || !Double.isFinite(minLexicalRatio) || minLexicalRatio < 0 || minLexicalRatio > 1)
            throw new IllegalArgumentException("关键词覆盖率和相对分数阈值必须介于 0 和 1");
    }
    public static RagSettings load() {
        return from(environment());
    }
    static Map<String, String> environment() {
        Map<String, String> values = new HashMap<>();
        Path file = Path.of(".env");
        if (Files.exists(file)) {
            try {
                for (String raw : Files.readAllLines(file)) {
                    String line = raw.strip();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.startsWith("export ")) line = line.substring(7).strip();
                    int split = line.indexOf('=');
                    if (split < 1) continue;
                    String value = line.substring(split + 1).strip();
                    if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                            || (value.startsWith("'") && value.endsWith("'"))))
                        value = value.substring(1, value.length() - 1);
                    values.put(line.substring(0, split).strip(), value);
                }
            } catch (Exception e) { throw new IllegalStateException("无法读取 .env", e); }
        }
        values.putAll(System.getenv());
        return values;
    }
    static RagSettings from(Map<String, String> v) {
        return new RagSettings(v.getOrDefault("RAG_DB", "data/rag.db"), v.getOrDefault("RAG_API_KEY", ""),
                v.getOrDefault("RAG_CHAT_BASE_URL", "http://localhost:11434/v1"), v.getOrDefault("RAG_CHAT_MODEL", ""),
                v.getOrDefault("RAG_CHAT_API_KEY", ""), v.getOrDefault("RAG_EMBED_BASE_URL", "http://localhost:11434/v1"),
                v.getOrDefault("RAG_EMBED_MODEL", ""), v.getOrDefault("RAG_EMBED_API_KEY", ""),
                Double.parseDouble(v.getOrDefault("RAG_TIMEOUT", "45")), Integer.parseInt(v.getOrDefault("RAG_CHUNK_SIZE", "800")),
                Integer.parseInt(v.getOrDefault("RAG_CHUNK_OVERLAP", "120")), Integer.parseInt(v.getOrDefault("RAG_CONTEXT_CHARS", "6000")),
                Double.parseDouble(v.getOrDefault("RAG_MIN_COSINE", "0.35")),
                Double.parseDouble(v.getOrDefault("RAG_MIN_LEXICAL_COVERAGE", "0.5")),
                Double.parseDouble(v.getOrDefault("RAG_MIN_LEXICAL_RATIO", "0.5")), JevSettings.from(v));
    }
    List<Object> signature() {
        return List.of(embedModel.isEmpty() ? "" : embedBaseUrl, embedModel, chunkSize, overlap);
    }
}
