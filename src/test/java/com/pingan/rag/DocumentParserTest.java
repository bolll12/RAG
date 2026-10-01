package com.pingan.rag;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class DocumentParserTest {
    @Test void legacyWordChineseText() throws Exception {
        try (var input = getClass().getResourceAsStream("/word/training.doc")) {
            var parsed = DocumentParser.parse("培训.DOC", input.readAllBytes());
            assertTrue(parsed.contains("培训目标"));
            assertTrue(parsed.contains("掌握知识库检索和文档切片。"));
            assertTrue(parsed.contains("每周三开展实践课程。"));
        }
    }
    @Test void docxParagraphAndTable() throws Exception {
        try (var document = new org.apache.poi.xwpf.usermodel.XWPFDocument();
             var output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("培训目标：掌握检索增强生成。");
            var table = document.createTable(1, 2);
            table.getRow(0).getCell(0).setText("课程时间");
            table.getRow(0).getCell(1).setText("每周三下午");
            document.write(output);
            var parsed = DocumentParser.parse("课程.DOCX", output.toByteArray());
            assertTrue(parsed.contains("培训目标：掌握检索增强生成。"));
            assertTrue(parsed.contains("课程时间"));
            assertTrue(parsed.contains("每周三下午"));
        }
    }
    @Test void rejectsInvalidAndEmptyWord() throws Exception {
        for (String suffix : new String[]{"doc", "docx"}) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> DocumentParser.parse("broken." + suffix, new byte[]{1, 2, 3}));
            assertTrue(error.getMessage().contains("Word 解析失败"));
        }
        try (var document = new org.apache.poi.xwpf.usermodel.XWPFDocument();
             var output = new ByteArrayOutputStream()) {
            document.write(output);
            var error = assertThrows(IllegalArgumentException.class,
                    () -> DocumentParser.parse("empty.docx", output.toByteArray()));
            assertTrue(error.getMessage().contains("没有可检索文本"));
        }
    }
    @Test void utf8BomAndErrors() {
        assertEquals("中文知识", DocumentParser.parse("x.md", "\uFEFF中文知识".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> DocumentParser.parse("x.txt", new byte[]{(byte)0xff}));
        assertThrows(IllegalArgumentException.class, () -> DocumentParser.parse("x.exe", new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> DocumentParser.parse("x.md", new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> DocumentParser.parse("x.pdf", new byte[]{1,2,3}));
        assertThrows(IllegalArgumentException.class, () -> DocumentParser.parse("x.txt", new byte[Contracts.MAX_BYTES + 1]));
    }
    @Test void realPdfTextAndPageMarkers() throws Exception {
        try (var pdf = new PDDocument(); var output = new ByteArrayOutputStream()) {
            for (int i = 0; i < 2; i++) {
                var page = new PDPage(); pdf.addPage(page);
                try (var content = new PDPageContentStream(pdf, page)) {
                    content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(40, 700); content.showText("RAG knowledge page " + (i+1)); content.endText();
                }
            }
            pdf.save(output);
            var parsed = DocumentParser.parse("test.pdf", output.toByteArray());
            assertTrue(parsed.contains("【PDF 第 1 页】"));
            assertTrue(parsed.contains("【PDF 第 2 页】"));
            assertTrue(parsed.contains("RAG knowledge page 2"));
        }
    }
}
