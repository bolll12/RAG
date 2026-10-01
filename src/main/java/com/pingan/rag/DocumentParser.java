package com.pingan.rag;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.Locale;

final class DocumentParser {
    static String parse(String filename, byte[] raw) {
        if (raw.length > Contracts.MAX_BYTES) throw new IllegalArgumentException("文件超过 10 MB 限制");
        String name = filename.toLowerCase(Locale.ROOT);
        String result;
        if (name.endsWith(".txt") || name.endsWith(".md")) {
            try {
                result = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
                if (result.startsWith("\uFEFF")) result = result.substring(1);
            } catch (CharacterCodingException e) { throw new IllegalArgumentException("TXT/Markdown 文件必须使用 UTF-8 编码"); }
        } else if (name.endsWith(".html") || name.endsWith(".htm")) {
            result = HtmlTextExtractor.extract(raw);
        } else if (name.endsWith(".doc") || name.endsWith(".docx")) {
            try (var input = new ByteArrayInputStream(raw)) {
                if (name.endsWith(".docx")) {
                    try (var document = new XWPFDocument(input);
                         var extractor = new XWPFWordExtractor(document)) {
                        result = extractor.getText();
                    }
                } else {
                    try (var extractor = new WordExtractor(input)) {
                        result = extractor.getText();
                    }
                }
            } catch (IOException | RuntimeException e) {
                throw new IllegalArgumentException("Word 解析失败，请确认文件为未加密且未损坏的 .doc 或 .docx 文档", e);
            }
        } else if (name.endsWith(".pdf")) {
            try (var pdf = Loader.loadPDF(raw)) {
                if (pdf.isEncrypted()) throw new IllegalArgumentException("暂不支持加密 PDF");
                StringBuilder output = new StringBuilder();
                var stripper = new PDFTextStripper();
                for (int i = 1; i <= pdf.getNumberOfPages(); i++) {
                    stripper.setStartPage(i); stripper.setEndPage(i);
                    var page = new LimitedWriter(Contracts.MAX_CHARS - output.length());
                    stripper.writeText(pdf, page);
                    if (!page.toString().isBlank()) output.append("\n\n【PDF 第 ").append(i).append(" 页】\n").append(page);
                    if (output.length() > Contracts.MAX_CHARS) throw new IllegalArgumentException("解析后正文超过长度限制");
                }
                result = output.toString();
            } catch (IllegalArgumentException e) { throw e; }
            catch (IOException e) { throw new IllegalArgumentException("PDF 解析失败，请检查文件是否损坏或加密", e); }
        } else throw new IllegalArgumentException("仅支持 .md、.txt、.html、.htm、.doc、.docx 和文本型 .pdf 文件");
        if (result.isBlank()) throw new IllegalArgumentException("文件没有可检索文本；扫描件需要先进行 OCR");
        Contracts.field(result, Contracts.MAX_CHARS, "解析后正文");
        return result;
    }
    private static final class LimitedWriter extends Writer {
        private final StringBuilder buffer = new StringBuilder();
        private final int limit;
        private LimitedWriter(int limit) { this.limit = limit; }
        @Override public void write(char[] chars, int offset, int length) {
            if (buffer.length() + length > limit) throw new IllegalArgumentException("解析后正文超过长度限制");
            buffer.append(chars, offset, length);
        }
        @Override public void flush() {}
        @Override public void close() {}
        @Override public String toString() { return buffer.toString(); }
    }
}
