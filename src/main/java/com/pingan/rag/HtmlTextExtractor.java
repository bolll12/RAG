package com.pingan.rag;

import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.jsoup.select.*;
import java.io.*;

final class HtmlTextExtractor {
    private HtmlTextExtractor() {}
    static String extract(byte[] raw) {
        try {
            // 只解析上传的字节；不请求链接、不加载资源、不执行 JavaScript。
            Document document = Jsoup.parse(new ByteArrayInputStream(raw), null, "");
            document.select("script,style,noscript,template,iframe,object,embed,svg,canvas,[hidden],[aria-hidden=true]").remove();
            StringBuilder output = new StringBuilder();
            NodeTraversor.traverse(new NodeVisitor() {
                private void append(String value) {
                    if (output.length() + value.length() > Contracts.MAX_CHARS)
                        throw new IllegalArgumentException("HTML 正文超过 200 万字符限制");
                    output.append(value);
                }
                private void newline() {
                    if (!output.isEmpty() && output.charAt(output.length()-1) != '\n') append("\n");
                }
                private boolean cell(Element element) {
                    return element.normalName().equals("td") || element.normalName().equals("th");
                }
                @Override public void head(Node node, int depth) {
                    if (node instanceof TextNode text) append(text.getWholeText().replaceAll("[\\s\\u00a0]+", " "));
                    else if (node instanceof Element element) {
                        if ((element.isBlock() && !cell(element)) || element.normalName().equals("br")) newline();
                        if (cell(element)) append("\t");
                    }
                }
                @Override public void tail(Node node, int depth) {
                    if (node instanceof Element element && element.isBlock() && !cell(element)) newline();
                }
            }, document.body());
            String body = output.toString().strip();
            if (body.isBlank()) throw new IllegalArgumentException("HTML 没有可检索正文；动态网页请先导出包含正文的 HTML");
            return document.title().isBlank() ? body : document.title() + "\n\n" + body;
        } catch (IOException e) {
            throw new IllegalArgumentException("HTML 解析失败，请检查文件编码和内容", e);
        }
    }
}
