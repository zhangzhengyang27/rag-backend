package org.example.parse.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.parse.MarkdownConverter;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * 网页正文抓取：jsoup 抓取 URL → 优先取 article/main/[role=main] 正文 →
 * {@link MarkdownConverter} 转 Markdown，返回 (title, markdown)。
 *
 * <p>SSRF 防护（服务端请求伪造）：
 * <ul>
 *   <li>仅允许 http/https 协议（拒绝 file:/ftp:/javascript: 等），重定向的每一跳都重新校验；</li>
 *   <li>解析主机名得到全部 IP，拒绝环回/链路本地（含云元数据 169.254.169.254）/内网/
 *       组播/0.0.0.0 及 IPv6 ULA 等保留地址；</li>
 *   <li>手动跟随重定向（上限 {@value #MAX_REDIRECTS} 跳），避免 jsoup 自动跟随绕过首跳校验；</li>
 *   <li>对外错误信息只保留分类原因（超时/无法解析/无法连接），底层异常细节仅记日志，
 *       防止被当作内网探测的 oracle。</li>
 * </ul></p>
 *
 * <p>已知残余风险：DNS rebinding（校验时与连接时解析结果不一致）——校验通过后仍按主机名连接，
 * 严格防护需按 IP 连接并覆盖 Host 头，当前规模下不引入该复杂度。</p>
 *
 * <p>允许通过 {@code app.kb.url-fetch.allow-private-hosts=true} 放行内网地址（本地联调导入
 * 内网文档站时使用），生产环境必须保持默认关闭。</p>
 */
@Slf4j
@Component
public class UrlFetcher {

    /** 抓取超时：防止慢速/无响应站点长期占用请求线程。 */
    private static final int TIMEOUT_MILLIS = 15_000;

    /** 重定向跟随上限，防止重定向环。 */
    private static final int MAX_REDIRECTS = 5;

    private static final String USER_AGENT =
            "Mozilla/5.0 (compatible; KnowledgeBaseBot/1.0; +https://tech-codex.local)";

    /** 是否放行内网/保留地址（仅本地联调可开，生产必须关闭）。 */
    private final boolean allowPrivateHosts;

    /** 抓取结果：网页标题（可能为 null）+ 正文 Markdown（非空）。 */
    public record FetchedPage(String title, String markdown) {
    }

    public UrlFetcher(@Value("${app.kb.url-fetch.allow-private-hosts:false}") boolean allowPrivateHosts) {
        this.allowPrivateHosts = allowPrivateHosts;
    }

    /**
     * 抓取并解析网页正文。
     *
     * @param url 目标 URL（http/https）
     * @return 标题与 Markdown 正文
     * @throws IllegalArgumentException URL 非法 / 被安全策略拒绝 / 抓取失败 / 无正文
     */
    public FetchedPage fetch(String url) {
        String current = validateAndResolve(url);
        try {
            org.jsoup.Connection.Response response = execute(current);
            int hops = 0;
            // 手动跟随重定向：每一跳都重新做协议 + 主机校验，防止 302 跳到内网绕过首跳检查
            while ((response.statusCode() == 301 || response.statusCode() == 302
                    || response.statusCode() == 307 || response.statusCode() == 308)
                    && response.header("Location") != null) {
                if (hops++ >= MAX_REDIRECTS) {
                    throw new IllegalArgumentException("重定向次数超过上限（" + MAX_REDIRECTS + "）: " + url);
                }
                String next = URI.create(current).resolve(response.header("Location")).toString();
                current = validateAndResolve(next);
                response = execute(current);
            }
            // 与 jsoup get() 行为对齐：非 2xx 视为抓取失败（如 404 页面不能被当作正文导入）
            if (response.statusCode() >= 400) {
                throw new IllegalArgumentException("抓取失败: " + url + "（HTTP " + response.statusCode() + "）");
            }
            Document doc = response.parse();
            String markdown = MarkdownConverter.render(selectMain(doc));
            if (markdown.isBlank()) {
                throw new IllegalArgumentException("未能从页面提取到正文: " + url);
            }
            String title = doc.title();
            if (title != null) {
                title = title.trim();
            }
            return new FetchedPage(title == null || title.isEmpty() ? null : title, markdown);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (SocketTimeoutException e) {
            log.warn("URL 抓取超时 - url: {}, err: {}", url, e.getMessage());
            throw new IllegalArgumentException("抓取超时: " + url);
        } catch (UnknownHostException e) {
            log.warn("URL 主机无法解析 - url: {}, err: {}", url, e.getMessage());
            throw new IllegalArgumentException("主机无法解析: " + hostOf(url));
        } catch (Exception e) {
            // 底层异常细节（含内网拓扑线索）只落日志，不回传给调用方
            log.warn("URL 抓取失败 - url: {}, err: {}", url, e.getMessage());
            throw new IllegalArgumentException("抓取失败，无法连接目标站点: " + url);
        }
    }

    /** 发起一次请求（关闭自动重定向与 jsoup 内置错误抛出，统一由 fetch 显式校验状态码）。 */
    private org.jsoup.Connection.Response execute(String url) throws Exception {
        return Jsoup.connect(url)
                .timeout(TIMEOUT_MILLIS)
                .userAgent(USER_AGENT)
                .followRedirects(false)
                .ignoreHttpErrors(true)
                .execute();
    }

    /**
     * 校验 URL 并做主机解析检查，通过后原样返回。
     * 协议白名单 + 主机 IP 黑名单（环回/链路本地/内网/组播/0.0.0.0/IPv6 ULA）。
     */
    private String validateAndResolve(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL 不能为空");
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("URL 格式非法");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("仅支持 http/https 协议的 URL");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL 缺少主机名");
        }
        if (allowPrivateHosts) {
            return url.trim();
        }
        for (InetAddress addr : resolveHost(host)) {
            if (isBlockedAddress(addr)) {
                log.warn("URL 指向内网/保留地址已拒绝 - host: {}, addr: {}", host, addr.getHostAddress());
                throw new IllegalArgumentException("URL 被拒绝：不允许访问内网或保留地址");
            }
        }
        return url.trim();
    }

    private InetAddress[] resolveHost(String host) {
        try {
            return InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("主机无法解析: " + host);
        }
    }

    /** 判断地址是否属于禁止抓取的保留/内网段。 */
    private static boolean isBlockedAddress(InetAddress addr) {
        if (addr.isLoopbackAddress() || addr.isAnyLocalAddress() || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress() || addr.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = addr.getAddress();
        // 0.0.0.0/8（isAnyLocalAddress 只覆盖 0.0.0.0 本身）
        if (bytes.length == 4 && bytes[0] == 0) {
            return true;
        }
        // IPv6 unique local address fc00::/7（isSiteLocalAddress 不覆盖）
        if (bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC) {
            return true;
        }
        return false;
    }

    private static String hostOf(String url) {
        try {
            String h = new URI(url.trim()).getHost();
            return h == null ? "" : h;
        } catch (Exception e) {
            return "";
        }
    }

    /** 正文选择：article/main/[role=main] 优先，回退整个 body。 */
    private static Element selectMain(Document doc) {
        Element main = doc.selectFirst("article, main, [role=main]");
        if (main != null) {
            return main;
        }
        Element body = doc.body();
        return body != null ? body : doc;
    }
}
