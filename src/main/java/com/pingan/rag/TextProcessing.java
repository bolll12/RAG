package com.pingan.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

final class TextProcessing {
    private static final Pattern LATIN = Pattern.compile("[a-z0-9_]+");
    private static final Pattern HAN = Pattern.compile("[\\u3400-\\u9fff]+");
    record Chunk(int start, int end, String text) {}
    private TextProcessing() {}
    static int length(String text) { return text.codePointCount(0, text.length()); }
    static String slice(String text, int start, int end) {
        return text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end));
    }
    static List<Chunk> chunks(String text, int size, int overlap) {
        return chunks(text, new ChunkOptions("paragraph", size, overlap));
    }
    static List<Chunk> chunks(String text, ChunkOptions options) {
        int size = options.size(), overlap = options.overlap();
        int[] points = text.codePoints().toArray();
        List<Chunk> result = new ArrayList<>();
        // 结构化策略先按 Markdown 章节分隔，重叠不跨章节；fixed 保持精确窗口。
        var boundaries = new TreeSet<Integer>();
        boundaries.add(0);
        if (!options.strategy().equals("fixed")) {
            for (var heading : headings(text)) boundaries.add(text.codePointCount(0, heading.start()));
        }
        boundaries.add(points.length);
        var limits = new ArrayList<>(boundaries);
        for (int section = 0; section + 1 < limits.size(); section++) {
            int start = limits.get(section), limit = limits.get(section + 1);
            while (start < limit) {
                int end = Math.min(start + size, limit);
                if (end < limit && !options.strategy().equals("fixed")) {
                    for (int i = end - 1; i >= start + size / 2; i--) {
                        if (boundary(points, i, options.strategy())) { end = i + 1; break; }
                    }
                }
                String part = new String(points, start, end - start);
                if (!part.isBlank()) result.add(new Chunk(start, end, part));
                if (end == limit) break;
                int next = Math.max(start + 1, end - overlap);
                if (!options.strategy().equals("fixed")) {
                    // overlap 是上限，优先从完整段落或句子开始；无边界则不重复半句。
                    while (next < end && !boundary(points, next - 1, options.strategy())) next++;
                }
                start = next;
            }
        }
        return result;
    }
    record Heading(int start, String title) {}
    static List<Heading> headings(String text) {
        var result = new ArrayList<Heading>();
        var lines = Pattern.compile("(?m)^.*$").matcher(text);
        char fence = 0;
        int fenceLength = 0;
        while (lines.find()) {
            String line = lines.group();
            var marker = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$").matcher(line);
            if (marker.matches()) {
                String run = marker.group(1);
                if (fence == 0) { fence = run.charAt(0); fenceLength = run.length(); }
                else if (run.charAt(0) == fence && run.length() >= fenceLength && marker.group(2).isBlank()) fence = 0;
                continue;
            }
            if (fence != 0) continue;
            var heading = Pattern.compile("^ {0,3}#{1,6}\\s+(.+)$").matcher(line);
            if (heading.matches()) result.add(new Heading(lines.start(), heading.group(1)));
        }
        return result;
    }
    private static boolean boundary(int[] points, int i, String strategy) {
        return (strategy.equals("paragraph") && points[i] == '\n')
                || "。！？!?；;".indexOf(points[i]) >= 0
                || (points[i] == '.' && (i + 1 == points.length || Character.isWhitespace(points[i + 1])));
    }
    static Set<String> queryTerms(String question) {
        // 疑问句模板不能成为章节定位依据，保留实际查询主题。
        String topic = question.replaceAll("(?:它)?适合解决哪些问题|(?:它)?能解决什么问题|为什么|什么是|是什么|有哪些|哪些|什么|如何|怎么|请问|介绍一下", " ");
        var terms = new HashSet<>(tokens(topic).keySet());
        terms.removeAll(Set.of("的", "了", "吗", "呢", "是", "有", "它", "和", "与", "在"));
        // 课程语料中的常见同义词；同时参与召回与覆盖率计算。
        if (terms.contains("培训") || terms.contains("学习")) {
            terms.add("培训");
            terms.add("学习");
        }
        return terms;
    }
    static Map<String, Integer> tokens(String text) {
        Map<String, Integer> result = new HashMap<>();
        LATIN.matcher(text.toLowerCase(Locale.ROOT)).results().forEach(m -> result.merge(m.group(), 1, Integer::sum));
        HAN.matcher(text).results().forEach(m -> {
            String run = m.group();
            if (run.length() == 1) result.merge(run, 1, Integer::sum);
            for (int i = 0; i < run.length() - 1; i++) result.merge(run.substring(i, i + 2), 1, Integer::sum);
        });
        return result;
    }
    static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
