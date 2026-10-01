package com.pingan.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

final class Json {
    static final ObjectMapper MAPPER = new ObjectMapper();
    private Json() {}
    static String write(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("JSON 序列化失败", e); }
    }
    static JsonNode read(String value) {
        try { return MAPPER.readTree(value); }
        catch (Exception e) { throw new IllegalArgumentException("JSON 格式无效", e); }
    }
    static Map<String, Object> object(String value) {
        try { return MAPPER.readValue(value, new TypeReference<>() {}); }
        catch (Exception e) { throw new IllegalArgumentException("JSON 对象格式无效", e); }
    }
    static Map<String, Object> map(Object... items) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < items.length; i += 2) result.put((String) items[i], items[i + 1]);
        return result;
    }
}
