package com.pingan.rag;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes={RagApplication.class, ApiIntegrationTest.Config.class})
@AutoConfigureMockMvc
class ApiIntegrationTest {
    @TestConfiguration static class Config {
        @Bean @Primary RagSettings testSettings() throws Exception {
            return RagSettings.from(Map.of("RAG_DB", Files.createTempDirectory("rag-java-test").resolve("rag.db").toString(), "RAG_API_KEY", "test-secret"));
        }
    }
    @Autowired MockMvc mvc;
    private static final String AUTH = "Bearer test-secret";
    @Test void wordUploadReplacementAndRetrieval() throws Exception {
        byte[] legacy;
        try (var input = getClass().getResourceAsStream("/word/training.doc")) { legacy = input.readAllBytes(); }
        var first = new MockMultipartFile("file", "training.doc", "application/msword", legacy);
        var response = mvc.perform(multipart("/documents/upload").file(first)
                        .param("collection", "word-test").param("chunk_strategy", "sentence")
                        .param("chunk_size", "100").param("chunk_overlap", "0").header("Authorization", AUTH))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = Json.read(response).get("document_id").asText();
        mvc.perform(post("/ask").header("Authorization", AUTH).contentType("application/json")
                        .content("{\"question\":\"培训目标\",\"collection\":\"word-test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("掌握知识库检索")));
        byte[] updated;
        try (var document = new org.apache.poi.xwpf.usermodel.XWPFDocument();
             var output = new java.io.ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("培训目标：掌握模型评估新技能。");
            document.write(output); updated = output.toByteArray();
        }
        var replacement = new MockMultipartFile("file", "updated.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", updated);
        mvc.perform(multipart("/documents/upload").file(replacement).param("collection", "word-test")
                        .param("source", "training.doc").header("Authorization", AUTH))
                .andExpect(status().isOk()).andExpect(jsonPath("$.document_id").value(id));
        mvc.perform(post("/ask").header("Authorization", AUTH).contentType("application/json")
                        .content("{\"question\":\"培训目标\",\"collection\":\"word-test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("掌握模型评估新技能")))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("掌握知识库检索"))));
        mvc.perform(delete("/documents/" + id).header("Authorization", AUTH)).andExpect(status().isOk());
        mvc.perform(multipart("/documents/upload").file(new MockMultipartFile("file", "broken.doc",
                        "application/msword", new byte[]{1, 2, 3})).header("Authorization", AUTH))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").exists());
    }
    @Test void pagesHealthAndDocumentation() throws Exception {
        mvc.perform(get("/")).andExpect(status().isFound()).andExpect(header().string("Location", "/chat"));
        mvc.perform(get("/chat")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("添加文档知识")));
        mvc.perform(get("/static/chat.js")).andExpect(status().isOk());
        mvc.perform(get("/health")).andExpect(status().isOk()).andExpect(jsonPath("$.runtime").value("java"));
        mvc.perform(get("/openapi.json")).andExpect(status().isOk()).andExpect(jsonPath("$.paths['/ask']").exists());
    }
    @Test void authUploadQueryAndDelete() throws Exception {
        mvc.perform(get("/documents")).andExpect(status().isUnauthorized());
        var file = new MockMultipartFile("file", "policy.md", "text/markdown", "苹果采购标准：每箱十二元。".getBytes(StandardCharsets.UTF_8));
        String response = mvc.perform(multipart("/documents/upload").file(file).param("collection","api-test").header("Authorization", AUTH))
                .andExpect(status().isOk()).andExpect(jsonPath("$.chunks").value(1)).andReturn().getResponse().getContentAsString();
        String id = Json.read(response).get("document_id").asText();
        mvc.perform(post("/ask").header("Authorization", AUTH).contentType("application/json")
                .content("{\"question\":\"苹果采购\",\"collection\":\"api-test\",\"top_k\":3}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("extractive"))
                .andExpect(jsonPath("$.citations[0].document_id").value(id));
        mvc.perform(get("/documents/"+id).header("Authorization", AUTH)).andExpect(status().isOk());
        mvc.perform(delete("/documents/"+id).header("Authorization", AUTH)).andExpect(status().isOk());
        mvc.perform(get("/documents/"+id).header("Authorization", AUTH)).andExpect(status().isNotFound());
    }
    @Test void malformedInputAndBlankQuestion() throws Exception {
        mvc.perform(post("/ask").header("Authorization",AUTH).contentType("application/json").content("{\"question\":\"  \"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").exists());
        mvc.perform(post("/ask").header("Authorization",AUTH).contentType("application/json").content("not-json"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/documents/upload").header("Authorization", AUTH)).andExpect(status().isBadRequest());
    }
}
