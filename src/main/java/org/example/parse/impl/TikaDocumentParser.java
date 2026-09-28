package org.example.parse.impl;

import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.ToXMLContentHandler;
import org.example.parse.DocumentParser;
import org.example.parse.MarkdownConverter;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 基于 Apache Tika 的多格式文档解析器：将 PDF/Word/Excel/PPT/HTML 等二进制文档
 * 统一解析为 XHTML，再经 {@link MarkdownConverter} 转成 Markdown。
 *
 * <p>要点：扩展名白名单（忽略大小写）、30s 解析超时（防止恶意/损坏文件卡线程）、
 * TikaInputStream 开启 zip-bomb 解压防护。Markdown 主路径不经过本类，保证无损。
 */
@Component
public class TikaDocumentParser implements DocumentParser {

    /** 解析超时：防止恶意或损坏文件长期占用解析线程。 */
    private static final long PARSE_TIMEOUT_SECONDS = 30;

    private static final Set<String> SUPPORTED_EXTENSIONS = new HashSet<>(Arrays.asList(
            "md", "markdown", "txt", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "html", "htm"));

    private final AutoDetectParser parser = new AutoDetectParser();

    @Override
    public String parse(byte[] bytes, String fileName) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("文档内容为空");
        }
        String ext = extensionOf(fileName);
        if (!SUPPORTED_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("不支持的文件类型: ." + ext);
        }
        // 每个解析请求使用独立守护线程 + 超时，避免损坏文件永久占住共享线程
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "tika-parse");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<String> future = executor.submit(() -> doParse(bytes));
            return future.get(PARSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IllegalArgumentException("文档解析超时（>30s）: " + fileName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("文档解析被中断: " + fileName);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalArgumentException("文档解析失败: " + fileName + " - " + cause.getMessage(), cause);
        } finally {
            executor.shutdownNow();
        }
    }

    private String doParse(byte[] bytes) throws Exception {
        Metadata metadata = new Metadata();
        // ToXMLContentHandler 将解析器的 SAX 事件序列化为 XHTML（保留段落/列表/标题结构），
        // 再经 jsoup 转 Markdown；Tika 2.9 的 BodyContentHandler 仅输出纯文本，结构会丢失
        ToXMLContentHandler handler = new ToXMLContentHandler();
        ParseContext context = new ParseContext();
        try (TikaInputStream tis = TikaInputStream.get(bytes)) {
            parser.parse(tis, handler, metadata, context);
        }
        String xhtml = handler.toString();
        if (xhtml == null || xhtml.isBlank()) {
            throw new IllegalArgumentException("未能从文档中提取到文本内容");
        }
        Document doc = MarkdownConverter.parse(xhtml);
        Element body = doc.body();
        if (body == null) {
            throw new IllegalArgumentException("未能从文档中提取到文本内容");
        }
        return MarkdownConverter.render(body);
    }

    private String extensionOf(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(idx + 1).toLowerCase(Locale.ROOT);
    }
}
