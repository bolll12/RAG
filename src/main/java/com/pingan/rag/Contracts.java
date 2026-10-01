package com.pingan.rag;

import com.fasterxml.jackson.annotation.JsonProperty;

final class Contracts {
    static final int MAX_BYTES = 10 * 1024 * 1024;
    static final int MAX_CHARS = 2_000_000;
    record DocumentInput(String text, String title, String source, String collection,
                         @JsonProperty("chunk_strategy") String chunkStrategy,
                         @JsonProperty("chunk_size") Integer chunkSize,
                         @JsonProperty("chunk_overlap") Integer chunkOverlap) {
        DocumentInput { if (collection == null) collection = "default"; }
    }
    record QuestionInput(String question, String collection, @JsonProperty("top_k") Integer topK) {
        QuestionInput {
            if (collection == null) collection = "default";
            if (topK == null) topK = 5;
        }
    }
    static void collection(String value) {
        if (value == null || !value.matches("(?U)^[\\w-]{1,64}$"))
            throw new IllegalArgumentException("知识库名称须为 1–64 位字母、数字、中文、下划线或连字符");
    }
    static void field(String value, int max, String label) {
        if (value == null || value.isBlank() || TextProcessing.length(value) > max)
            throw new IllegalArgumentException(label + "不能为空，且不能超过 " + max + " 个字符");
    }
    static void validateDocument(String text, String title, String source, String collection) {
        field(text, MAX_CHARS, "正文"); field(title, 200, "标题"); field(source, 500, "来源"); collection(collection);
    }
    static void validateQuestion(String question, String collection, int topK) {
        field(question, 2000, "问题"); collection(collection);
        if (topK < 1 || topK > 20) throw new IllegalArgumentException("top_k 必须介于 1 和 20");
    }
}
