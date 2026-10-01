package com.pingan.rag;

import java.nio.file.*;
import java.util.*;

final class RagCli {
    static int run(String[] args) throws Exception {
        var settings = RagSettings.load();
        var store = new RagStore(settings);
        var service = new RagService(settings, store, new CompatibleModelClient(settings));
        if (args[0].equals("reindex")) {
            for (var row : store.read(db -> RagStore.query(db, "SELECT * FROM documents"))) {
                var options = new ChunkOptions((String)row.get("chunk_strategy"),
                        ((Number)row.get("chunk_size")).intValue(), ((Number)row.get("chunk_overlap")).intValue());
                System.out.println(Json.write(service.ingest((String)row.get("text"), (String)row.get("title"),
                        (String)row.get("source"), (String)row.get("collection"), options)));
            }
            return 0;
        }
        String collection = "default";
        int topK = 5;
        Path output = null;
        List<Path> files = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--collection" -> collection = args[++i];
                case "--top-k" -> topK = Integer.parseInt(args[++i]);
                case "--output" -> output = Path.of(args[++i]);
                default -> files.add(Path.of(args[i]));
            }
        }
        if (files.isEmpty()) throw new IllegalArgumentException("请提供文件路径");
        if (args[0].equals("ingest")) {
            for (var file : files) {
                byte[] raw;
                try (var in = Files.newInputStream(file)) { raw = in.readNBytes(Contracts.MAX_BYTES + 1); }
                String source = file.getFileName().toString();
                String title = source.replaceFirst("\\.[^.]+$", "");
                System.out.println(Json.write(service.ingest(DocumentParser.parse(source, raw), title, source, collection)));
            }
            return 0;
        }
        if (topK < 1 || topK > 20) throw new IllegalArgumentException("top-k 必须介于 1 和 20");
        List<Map<String, Object>> results = new ArrayList<>();
        double recallSum = 0, rrSum = 0;
        int positive = 0, passed = 0;
        for (String line : Files.readAllLines(files.getFirst())) {
            if (line.isBlank()) continue;
            var sample = Json.read(line);
            if (!sample.path("expected_sources").isArray()) throw new IllegalArgumentException("评测行缺少 expected_sources 数组");
            Set<String> expected = new HashSet<>();
            sample.get("expected_sources").forEach(node -> expected.add(node.asText()));
            var hits = service.search(sample.path("question").asText(), sample.path("collection").asText("default"), topK);
            var sources = hits.stream().map(hit -> (String) hit.get("source")).toList();
            Double recall = null, rr = null;
            boolean pass;
            if (expected.isEmpty()) pass = hits.isEmpty();
            else {
                recall = expected.stream().filter(sources::contains).count() / (double) expected.size();
                rr = 0.0;
                for (int i = 0; i < sources.size(); i++) if (expected.contains(sources.get(i))) { rr = 1.0 / (i + 1); break; }
                positive++; recallSum += recall; rrSum += rr; pass = recall == 1;
            }
            if (pass) passed++;
            results.add(Json.map("question", sample.path("question").asText(), "sources", sources, "passed", pass,
                    "recall_at_k", recall, "reciprocal_rank", rr));
        }
        if (results.isEmpty()) throw new IllegalArgumentException("评测集不能为空");
        var report = Json.map("cases", results.size(), "top_k", topK, "recall_at_k", positive == 0 ? null : recallSum / positive,
                "mrr_at_k", positive == 0 ? null : rrSum / positive, "pass_rate", passed / (double) results.size(), "results", results);
        String json = Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        if (output != null) {
            Files.createDirectories(output.toAbsolutePath().getParent()); Files.writeString(output, json);
        }
        System.out.println(json);
        return passed == results.size() ? 0 : 1;
    }
}
