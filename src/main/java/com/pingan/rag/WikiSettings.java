package com.pingan.rag;

import java.util.Map;

public record WikiSettings(String model, String baseUrl, String apiKey, int batchChars, int timeout, boolean autoUpdate) {
    public WikiSettings {
        if (batchChars < 500 || batchChars > 4000 || timeout < 1 || timeout > 1800)
            throw new IllegalArgumentException("Wiki 输入预算须为 500–4000 字符，超时须为 1–1800 秒");
        var uri = java.net.URI.create(baseUrl);
        boolean local = java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost() == null ? "" : uri.getHost());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && local)))
            throw new IllegalArgumentException("Wiki 模型地址须为 HTTPS；本机服务可使用 HTTP");
    }
    static WikiSettings from(Map<String, String> values) {
        return new WikiSettings(values.getOrDefault("RAG_WIKI_MODEL", values.getOrDefault("RAG_CHAT_MODEL", "")),
                values.getOrDefault("RAG_WIKI_BASE_URL", values.getOrDefault("RAG_CHAT_BASE_URL", "http://127.0.0.1:11434/v1")),
                values.getOrDefault("RAG_WIKI_API_KEY", values.getOrDefault("RAG_CHAT_API_KEY", "")),
                Integer.parseInt(values.getOrDefault("RAG_WIKI_BATCH_CHARS", "2500")),
                Integer.parseInt(values.getOrDefault("RAG_WIKI_TIMEOUT", "300")),
                Boolean.parseBoolean(values.getOrDefault("RAG_WIKI_AUTO_UPDATE", "true")));
    }
    boolean enabled() { return !model.isBlank(); }
    String signature() { return TextProcessing.hash("wiki-v2\0" + baseUrl + "\0" + model); }
    CompatibleModelClient client() {
        return new CompatibleModelClient(RagSettings.from(Map.of("RAG_CHAT_MODEL", model, "RAG_CHAT_BASE_URL", baseUrl,
                "RAG_CHAT_API_KEY", apiKey, "RAG_TIMEOUT", String.valueOf(timeout))));
    }
    @Override public String toString() { return "WikiSettings[model=" + model + ", apiKey=***]"; }
}
