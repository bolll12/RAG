package com.pingan.rag;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@SecurityRequirement(name="bearerAuth")
final class WikiController {
    private final WikiService wiki;
    WikiController(WikiService wiki) { this.wiki = wiki; }
    record BuildInput(String collection) {
        BuildInput { if (collection == null) collection = "default"; }
    }
    record AskInput(String question, String collection, boolean save) {
        AskInput { if (collection == null) collection = "default"; }
    }
    @GetMapping(value="/wiki", produces="text/html;charset=UTF-8") @Operation(hidden=true)
    ClassPathResource view() { return new ClassPathResource("static/wiki.html"); }
    @GetMapping("/api/wiki/status")
    Map<String, Object> status(@RequestParam(defaultValue="default") String collection) { return wiki.status(collection); }
    @PostMapping("/api/wiki/build")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> build(@RequestBody BuildInput input) { return wiki.build(input.collection()); }
    @GetMapping("/api/wiki/pages")
    List<Map<String, Object>> pages(@RequestParam(defaultValue="default") String collection,
                                   @RequestParam(defaultValue="") String q) { return wiki.pages(collection, q); }
    @GetMapping("/api/wiki/pages/{id}")
    Map<String, Object> page(@PathVariable String id, @RequestParam(defaultValue="default") String collection) { return wiki.page(id, collection); }
    @GetMapping("/api/wiki/pages/{id}/revisions")
    List<Map<String, Object>> revisions(@PathVariable String id, @RequestParam(defaultValue="default") String collection) { return wiki.revisions(id, collection); }
    @GetMapping(value="/api/wiki/pages/{id}/export", produces="text/markdown;charset=UTF-8")
    ResponseEntity<String> export(@PathVariable String id, @RequestParam(defaultValue="default") String collection) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=wiki.md").body(wiki.markdown(id, collection));
    }
    @PostMapping("/api/wiki/ask")
    Map<String, Object> ask(@RequestBody AskInput input) { return wiki.ask(input.question(), input.collection(), input.save()); }
    @GetMapping("/api/wiki/lint")
    Map<String, Object> lint(@RequestParam(defaultValue="default") String collection) { return wiki.lint(collection); }
}
