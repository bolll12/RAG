package com.pingan.rag;

import java.util.*;

final class RelevantExcerpt {
    record Section(String text, double score) {}
    private RelevantExcerpt() {}
    static double headingScore(String question, String text) {
        return sections(question, text).stream().mapToDouble(Section::score).max().orElse(0);
    }
    static List<Section> sections(String question, String text) {
        var headings = TextProcessing.headings(text);
        var terms = TextProcessing.queryTerms(question);
        List<Section> result = new ArrayList<>();
        for (int i = 0; i < headings.size(); i++) {
            var heading = headings.get(i);
            var words = TextProcessing.tokens(heading.title()).keySet();
            double score = terms.stream().filter(words::contains).count();
            if (score == 0) continue;
            int end = i + 1 < headings.size() ? headings.get(i + 1).start() : text.length();
            result.add(new Section(text.substring(heading.start(), end).strip(), score));
        }
        return result;
    }
    static String answer(String question, List<Map<String, Object>> hits) {
        var query = new QueryRelevance(question);
        String best = "";
        int citation = 0;
        double score = 0;
        for (var hit : hits) {
            String text = (String)hit.get("text");
            // 标题章节与正文片段共同竞争，避免弱标题命中压过明确的正文答案。
            var candidates = new ArrayList<Section>();
            for (var section : sections(question, text)) {
                String body = section.text().replaceFirst("^[^\\n]*(?:\\n|$)", "").strip();
                if (!body.isBlank()) {
                    String heading = section.text().split("\\n", 2)[0];
                    candidates.add(new Section(section.text(), query.coverage(section.text())
                            + 0.4 * query.coverage(heading)));
                }
            }
            for (String paragraph : text.split("\\n\\s*\\n|(?m)(?=^\\|)")) {
                String part = paragraph.strip();
                if (part.isBlank() || part.matches("#{1,6}[^\\n]*")) continue;
                candidates.add(new Section(part, query.coverage(part) * QueryRelevance.repetitionPenalty(part)));
            }
            for (var candidate : candidates) {
                if (!query.matchesTopic(candidate.text()) || !query.supportsDefinition(candidate.text())) continue;
                if (candidate.score() > score) {
                    best = candidate.text(); score = candidate.score();
                    citation = ((Number)hit.get("citation")).intValue();
                }
            }
        }
        if (best.isEmpty()) return RagService.REFUSAL;
        if (query.isDefinition()) best = query.definitionExcerpt(best);
        if (TextProcessing.length(best) > 800) best = TextProcessing.slice(best, 0, 800) + "\n…（摘录已截断，请展开引用查看完整片段）";
        return "[" + citation + "] " + best;
    }
}
