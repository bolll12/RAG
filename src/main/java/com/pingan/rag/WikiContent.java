package com.pingan.rag;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

final class WikiContent {
    private WikiContent() {}
    record Evidence(@JsonProperty("document_id") String documentId, int version, String title, String source,
                    String quote, int start, int end) {}
    record Fact(String text, List<Evidence> citations) {}
    record Page(String id, String title, String kind, List<Fact> facts, List<String> links) {}
    static String topicKey(String title) { return title.strip().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " "); }
    static String id(String collection, String key) { return TextProcessing.hash(collection + "\0wiki\0" + key).substring(0, 32); }
}
