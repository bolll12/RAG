package com.pingan.rag;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.util.*;

@RestController
@SecurityScheme(name="bearerAuth", type=SecuritySchemeType.HTTP, scheme="bearer")
public class RagController {
    private final RagSettings settings;
    private final RagStore store;
    private final RagService rag;
    RagController(RagSettings settings, RagStore store, RagService rag) {
        this.settings = settings; this.store = store; this.rag = rag;
    }
    @GetMapping("/") @Operation(hidden=true)
    ResponseEntity<Void> root() { return ResponseEntity.status(302).header("Location", "/chat").build(); }
    @GetMapping(value="/chat", produces="text/html;charset=UTF-8") @Operation(hidden=true)
    ClassPathResource chat() { return new ClassPathResource("static/chat.html"); }
    @GetMapping("/health")
    Map<String, Object> health() {
        return Json.map("status", "ok", "retrieval", settings.embedModel().isEmpty() ? "bm25" : "hybrid",
                "reranking", settings.jev().enabled() ? "jev" : "disabled",
                "jev_model", settings.jev().enabled() ? settings.jev().model() : null,
                "generation", settings.chatModel().isEmpty() ? "extractive" : "model", "auth_enabled", !settings.apiKey().isEmpty(),
                "runtime", "java", "java_version", System.getProperty("java.version"));
    }
    @PostMapping("/documents") @SecurityRequirement(name="bearerAuth")
    Map<String, Object> ingest(@RequestBody Contracts.DocumentInput body) {
        return rag.ingest(body.text(), body.title(), body.source(), body.collection(),
                ChunkOptions.resolve(body.chunkStrategy(), body.chunkSize(), body.chunkOverlap(), settings));
    }
    @PostMapping(value="/documents/upload", consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @SecurityRequirement(name="bearerAuth")
    Map<String, Object> upload(@RequestParam MultipartFile file, @RequestParam(defaultValue="default") String collection,
                              @RequestParam(required=false) String source,
                              @RequestParam(name="chunk_strategy", required=false) String strategy,
                              @RequestParam(name="chunk_size", required=false) Integer size,
                              @RequestParam(name="chunk_overlap", required=false) Integer overlap) throws IOException {
        Contracts.collection(collection);
        var options = ChunkOptions.resolve(strategy, size, overlap, settings);
        String filename = Optional.ofNullable(file.getOriginalFilename()).orElse("document.txt").replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1);
        if (filename.isBlank()) filename = "document.txt";
        String content;
        try (var stream = file.getInputStream()) {
            content = DocumentParser.parse(filename, stream.readNBytes(Contracts.MAX_BYTES + 1));
        }
        String title = TextProcessing.slice(filename, 0, Math.min(200, TextProcessing.length(filename)));
        return rag.ingest(content, title, source == null ? filename : source, collection, options);
    }
    @GetMapping("/documents") @SecurityRequirement(name="bearerAuth")
    List<Map<String, Object>> documents(@RequestParam(defaultValue="default") String collection) {
        Contracts.collection(collection); return store.documents(collection);
    }
    @GetMapping("/documents/{id}") @SecurityRequirement(name="bearerAuth")
    Map<String, Object> document(@PathVariable String id) {
        var result = store.document(id);
        if (result == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在");
        return result;
    }
    @DeleteMapping("/documents/{id}") @SecurityRequirement(name="bearerAuth")
    Map<String, Object> delete(@PathVariable String id) {
        if (!store.delete(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在");
        return Json.map("deleted", true);
    }
    @PostMapping("/search") @SecurityRequirement(name="bearerAuth")
    Map<String, Object> search(@RequestBody Contracts.QuestionInput body) {
        return Json.map("hits", rag.search(body.question(), body.collection(), body.topK()));
    }
    @PostMapping("/ask") @SecurityRequirement(name="bearerAuth")
    Map<String, Object> ask(@RequestBody Contracts.QuestionInput body) {
        return rag.ask(body.question(), body.collection(), body.topK());
    }
    @GetMapping("/traces/{id}") @SecurityRequirement(name="bearerAuth")
    Map<String, Object> trace(@PathVariable String id) {
        var result = store.trace(id);
        if (result == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "追踪记录不存在");
        return result;
    }
}
