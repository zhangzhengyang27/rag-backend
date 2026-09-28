package org.example.parse;

import com.sun.net.httpserver.HttpServer;
import org.example.parse.impl.UrlFetcher;
import org.example.parse.impl.UrlFetcher.FetchedPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UrlFetcher 单测：基于 JDK 内嵌 HttpServer 提供本地页面，
 * 断言能提取 article 正文并转 Markdown、剔除导航噪音、拒绝非法 URL 与空正文。
 */
class UrlFetcherTest {

    private HttpServer server;
    private UrlFetcher fetcher;

    @BeforeEach
    void setUp() throws IOException {
        // 测试服务跑在 127.0.0.1：本地联调模式（allowPrivateHosts=true）下才可抓取
        fetcher = new UrlFetcher(true);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/article", exchange -> {
            byte[] body = """
                    <html><head><title>测试页面</title></head><body>
                    <nav>导航链接</nav>
                    <article>
                      <h1>知识库标题</h1>
                      <p>这是一段正文内容。</p>
                      <ul><li>条目一</li><li>条目二</li></ul>
                    </article>
                    </body></html>""".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/empty", exchange -> {
            byte[] body = "<html><body><nav>只有导航</nav></body></html>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/404", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String url(String path) {
        return "http://localhost:" + server.getAddress().getPort() + path;
    }

    @Test
    void fetchesArticleToMarkdown() {
        FetchedPage page = fetcher.fetch(url("/article"));
        assertThat(page.title()).isEqualTo("测试页面");
        assertThat(page.markdown()).contains("# 知识库标题");
        assertThat(page.markdown()).contains("这是一段正文内容");
        assertThat(page.markdown()).contains("- 条目一");
        // 优先 article 后导航噪音应被剔除
        assertThat(page.markdown()).doesNotContain("导航链接");
    }

    @Test
    void rejectsInvalidScheme() {
        assertThatThrownBy(() -> fetcher.fetch("file:///etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仅支持 http/https");
    }

    @Test
    void rejectsBlankUrl() {
        assertThatThrownBy(() -> fetcher.fetch("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL 不能为空");
    }

    @Test
    void rejectsEmptyBody() {
        assertThatThrownBy(() -> fetcher.fetch(url("/empty")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未能从页面提取到正文");
    }

    @Test
    void rejectsHttpErrorStatus() {
        assertThatThrownBy(() -> fetcher.fetch(url("/404")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP 404");
    }

    @Test
    void rejectsLoopbackAddressByDefault() {
        // 生产默认（allowPrivateHosts=false）：环回地址属于 SSRF 黑名单
        UrlFetcher strictFetcher = new UrlFetcher(false);
        assertThatThrownBy(() -> strictFetcher.fetch("http://localhost:" + server.getAddress().getPort() + "/article"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内网或保留地址");
        assertThatThrownBy(() -> strictFetcher.fetch("http://127.0.0.1:" + server.getAddress().getPort() + "/article"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内网或保留地址");
        assertThatThrownBy(() -> strictFetcher.fetch("http://169.254.169.254/latest/meta-data"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内网或保留地址");
    }
}
