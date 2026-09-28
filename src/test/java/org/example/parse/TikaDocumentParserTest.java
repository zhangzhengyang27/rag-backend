package org.example.parse;

import org.example.parse.impl.TikaDocumentParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TikaDocumentParser 单测：DOCX/PDF 能解析为 Markdown、不支持扩展名/空内容被拒绝。
 * 测试资源用代码动态生成最小合法 DOCX（zip）与 PDF，避免向仓库提交二进制文件。
 */
class TikaDocumentParserTest {

    private final DocumentParser parser = new TikaDocumentParser();

    @Test
    void parsesDocxToMarkdown() {
        byte[] docx = sampleDocx("你好，知识库", "Hello Knowledge Base");
        String md = parser.parse(docx, "sample.docx");
        assertThat(md).contains("你好").contains("知识库");
    }

    @Test
    void parsesPdfToText() {
        byte[] pdf = samplePdf("Hello Knowledge Base");
        String md = parser.parse(pdf, "sample.pdf");
        assertThat(md).isNotBlank();
    }

    @Test
    void parsesHtmlToMarkdown() {
        String html = "<html><body><h1>标题</h1><p>正文内容</p><ul><li>条目一</li><li>条目二</li></ul></body></html>";
        String md = parser.parse(html.getBytes(StandardCharsets.UTF_8), "sample.html");
        assertThat(md).contains("# 标题").contains("正文内容").contains("- 条目一");
    }

    @Test
    void rejectsUnsupportedExtension() {
        byte[] exe = "MZ fake binary".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> parser.parse(exe, "sample.exe"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的文件类型");
    }

    @Test
    void rejectsEmptyContent() {
        assertThatThrownBy(() -> parser.parse(new byte[0], "sample.pdf"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内容为空");
    }

    // ---- 测试资源生成 ----

    private byte[] sampleDocx(String... paragraphs) {
        StringBuilder body = new StringBuilder();
        for (String p : paragraphs) {
            body.append("<w:p><w:r><w:t>").append(p).append("</w:t></w:r></w:p>");
        }
        String documentXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:body>" + body + "</w:body></w:document>";
        String contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "</Types>";
        String rels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                + "</Relationships>";
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(bos)) {
            put(zip, "[Content_Types].xml", contentTypes);
            put(zip, "_rels/.rels", rels);
            put(zip, "word/document.xml", documentXml);
            zip.finish();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("生成测试 DOCX 失败", e);
        }
    }

    private void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private byte[] samplePdf(String text) {
        String content = "BT /F1 24 Tf 72 720 Td (" + text + ") Tj ET";
        List<Long> offsets = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        long pos = 0;
        String header = "%PDF-1.4\n";
        sb.append(header);
        pos += header.length();
        String obj1 = "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n";
        offsets.add(pos);
        sb.append(obj1);
        pos += obj1.length();
        String obj2 = "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n";
        offsets.add(pos);
        sb.append(obj2);
        pos += obj2.length();
        String obj3 = "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R "
                + "/Resources << /Font << /F1 5 0 R >> >> >>\nendobj\n";
        offsets.add(pos);
        sb.append(obj3);
        pos += obj3.length();
        String obj4Head = "4 0 obj\n<< /Length " + content.length() + " >>\nstream\n";
        offsets.add(pos);
        sb.append(obj4Head);
        pos += obj4Head.length();
        sb.append(content);
        pos += content.length();
        String obj4Tail = "\nendstream\nendobj\n";
        sb.append(obj4Tail);
        pos += obj4Tail.length();
        String obj5 = "5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n";
        offsets.add(pos);
        sb.append(obj5);
        pos += obj5.length();
        long xrefPos = pos;
        StringBuilder xref = new StringBuilder("xref\n0 6\n0000000000 65535 f \n");
        for (long o : offsets) {
            xref.append(String.format("%010d 00000 n \n", o));
        }
        xref.append("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n").append(xrefPos).append("\n%%EOF\n");
        sb.append(xref);
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }
}
