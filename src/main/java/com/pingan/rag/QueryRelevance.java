package com.pingan.rag;

import java.util.*;
import java.util.regex.Pattern;

/** 词法证据校验用于保守摘录，不等同于模型的语义判断。 */
final class QueryRelevance {
    private static final Pattern PREFIX = Pattern.compile("什么是\\s*([^？?。！!，,；;]+)");
    private static final Pattern SUFFIX = Pattern.compile("^\\s*(?:请问\\s*)?(.+?)(?:是什么|的定义是什么)");
    private final Set<String> terms;
    private final Set<String> namedTerms;
    private final String subject;

    QueryRelevance(String question) {
        terms = TextProcessing.queryTerms(question);
        namedTerms = new HashSet<>();
        for (String term : terms) if (term.matches("[a-z][a-z0-9_]*")) namedTerms.add(term);
        var prefix = PREFIX.matcher(question);
        var suffix = SUFFIX.matcher(question);
        subject = prefix.find() ? prefix.group(1).strip()
                : suffix.find() ? suffix.group(1).strip() : null;
    }

    boolean matchesTopic(String text) {
        var words = TextProcessing.tokens(text).keySet();
        return namedTerms.isEmpty() || namedTerms.stream().anyMatch(words::contains);
    }

    double coverage(String text) {
        if (terms.isEmpty()) return 0;
        var words = TextProcessing.tokens(text).keySet();
        double matched = 0, total = 0;
        for (String term : terms) {
            double weight = namedTerms.contains(term) ? 2 : 1;
            total += weight;
            if (words.contains(term)) matched += weight;
        }
        return matched / total;
    }

    static double repetitionPenalty(String text) {
        var counts = TextProcessing.tokens(text);
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        return total == 0 ? 1 : Math.min(1, 3.0 * counts.size() / total);
    }

    boolean isDefinition() { return subject != null; }

    boolean supportsDefinition(String text) {
        if (subject == null) return true;
        return definitionExcerpt(text) != null;
    }

    String definitionExcerpt(String text) {
        if (subject == null) return null;
        // 保守识别明确解释语句，课程目录、未来安排中的关键词提及不算定义证据。
        String clean = text.replace("**", "").replace("`", "");
        String topic = Pattern.quote(subject);
        String explanation = "(?:是(?!否)|指(?:的?是)?|即|表示|用于|通过|是一种|is\\b|means\\b|refers to\\b)";
        String boundary = subject.matches("[A-Za-z0-9_ ]+") ? "(?<![A-Za-z0-9_])" : "";
        var explicit = Pattern.compile(boundary + topic
                + "\\s*(?:[（(][^）)\\r\\n]{1,160}[）)]\\s*)?"
                + explanation + "[^。！？!?\\r\\n]{3,}[。！？!?]?", Pattern.CASE_INSENSITIVE);
        var match = explicit.matcher(clean);
        if (match.find()) return match.group().strip();
        // 支持术语表中“术语：释义”的写法，但不把表格中的课程名称当作定义。
        match = Pattern.compile("(?im)^\\s*(?:[-*]\\s+)?" + topic
                + "\\s*[:：]\\s*[^\\r\\n|。！？!?]{6,}[。！？!?]?").matcher(clean);
        return match.find() ? match.group().strip() : null;
    }
}
