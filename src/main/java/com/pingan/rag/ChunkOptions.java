package com.pingan.rag;

import java.util.Map;
import java.util.Set;

public record ChunkOptions(String strategy, int size, int overlap) {
    public ChunkOptions {
        if (!Set.of("paragraph", "fixed", "sentence").contains(strategy))
            throw new IllegalArgumentException("切片方式须为 paragraph、fixed 或 sentence");
        if (size < 100 || size > 4000 || overlap < 0 || overlap >= size)
            throw new IllegalArgumentException("片段长度须为 100–4000 字符，重叠长度须为 0 至片段长度减一");
    }
    static ChunkOptions resolve(String strategy, Integer size, Integer overlap, RagSettings settings) {
        return new ChunkOptions(strategy == null ? "paragraph" : strategy,
                size == null ? settings.chunkSize() : size, overlap == null ? settings.overlap() : overlap);
    }
    boolean matches(Map<String, Object> row) {
        return strategy.equals(row.get("chunk_strategy")) && size == ((Number)row.get("chunk_size")).intValue()
                && overlap == ((Number)row.get("chunk_overlap")).intValue();
    }
}
