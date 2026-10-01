package com.pingan.rag;

import java.net.URI;
import java.util.Map;

record JevSettings(String endpoint, String model, String apiKey, double threshold, int candidates, int timeout) {
    JevSettings {
        var uri = URI.create(endpoint);
        boolean local = "127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost());
        if (uri.getHost() == null || uri.getUserInfo() != null
                || !("https".equals(uri.getScheme()) || (local && "http".equals(uri.getScheme()))))
            throw new IllegalArgumentException("Jev 地址必须使用 HTTPS（本地测试允许 HTTP）");
        if (model.isBlank() || !Double.isFinite(threshold) || threshold < 0 || threshold > 1
                || candidates < 1 || candidates > 20 || timeout < 1 || timeout > 180)
            throw new IllegalArgumentException("Jev 配置无效：阈值 0..1、候选数 1..20、超时 1..180 秒");
        if (apiKey.equals("YOUR_API_KEY")) throw new IllegalArgumentException("请配置真实 Jev 密钥或留空禁用");
    }
    boolean enabled() { return !apiKey.isBlank(); }
    @Override public String toString() { return "JevSettings[model=" + model + ", enabled=" + enabled() + "]"; }
    static JevSettings from(Map<String,String> values) {
        return new JevSettings(values.getOrDefault("RAG_JEV_URL", "https://tokendance.space/gateway/typesafe/v1/systemone"),
                values.getOrDefault("RAG_JEV_MODEL", "bocha-jev-v1"), values.getOrDefault("RAG_JEV_API_KEY", ""),
                Double.parseDouble(values.getOrDefault("RAG_JEV_THRESHOLD", "0.7")),
                Integer.parseInt(values.getOrDefault("RAG_JEV_CANDIDATES", "10")),
                Integer.parseInt(values.getOrDefault("RAG_JEV_TIMEOUT", "30")));
    }
}
