package org.example.parse;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 将 HTML / XHTML 元素树转换为 Markdown 文本。
 *
 * <p>由 {@link org.example.parse.impl.TikaDocumentParser}（XHTML→Markdown）与
 * {@link org.example.parse.impl.UrlFetcher}（网页正文→Markdown）共用：
 * 保留标题层级、段落、列表、代码块、引用、表格与行内加粗/斜体/链接/行内代码。
 */
public final class MarkdownConverter {

    private MarkdownConverter() {
    }

    /** 渲染整棵 body 子树为 Markdown。 */
    public static String render(Element root) {
        StringBuilder sb = new StringBuilder();
        for (Element child : root.children()) {
            renderBlock(child, sb);
        }
        return sb.toString().replaceAll("\n{3,}", "\n\n").trim();
    }

    private static void renderBlock(Element el, StringBuilder sb) {
        String tag = el.tagName();
        switch (tag) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                int level = tag.charAt(1) - '0';
                sb.append("\n").append("#".repeat(level)).append(' ').append(inline(el)).append("\n\n");
            }
            case "p" -> {
                String text = inline(el);
                if (!text.isBlank()) {
                    sb.append("\n").append(text).append("\n\n");
                }
            }
            case "ul", "ol" -> {
                int index = 1;
                for (Element li : el.children()) {
                    renderListItem(li, sb, "ul".equals(tag) ? "-" : (index++) + ".", 0);
                }
                sb.append("\n");
            }
            case "pre" -> sb.append("\n```\n").append(el.text()).append("\n```\n\n");
            case "blockquote" -> {
                String text = inline(el);
                if (!text.isBlank()) {
                    sb.append("\n> ").append(text).append("\n\n");
                }
            }
            case "hr" -> sb.append("\n---\n\n");
            case "table" -> renderTable(el, sb);
            default -> {
                // div / section / article 等容器：递归渲染子块
                for (Element child : el.children()) {
                    renderBlock(child, sb);
                }
            }
        }
    }

    private static void renderListItem(Element li, StringBuilder sb, String marker, int depth) {
        String indent = "  ".repeat(depth);
        String text = inline(li);
        if (!text.isBlank()) {
            sb.append(indent).append(marker).append(' ').append(text).append('\n');
        }
        for (Element child : li.children()) {
            String childTag = child.tagName();
            if ("ul".equals(childTag) || "ol".equals(childTag)) {
                int index = 1;
                for (Element subLi : child.children()) {
                    renderListItem(subLi, sb, "ul".equals(childTag) ? "-" : (index++) + ".", depth + 1);
                }
            }
        }
    }

    private static void renderTable(Element table, StringBuilder sb) {
        List<Element> rows = table.select("tr");
        if (rows.isEmpty()) {
            return;
        }
        List<String[]> data = new ArrayList<>();
        int cols = 0;
        for (Element row : rows) {
            List<Element> cells = row.select("th, td");
            String[] line = new String[cells.size()];
            for (int i = 0; i < cells.size(); i++) {
                line[i] = cells.get(i).text().trim().replace('|', '/');
            }
            cols = Math.max(cols, cells.size());
            data.add(line);
        }
        if (cols == 0) {
            return;
        }
        sb.append('\n');
        for (int r = 0; r < data.size(); r++) {
            String[] line = data.get(r);
            sb.append('|');
            for (int c = 0; c < cols; c++) {
                sb.append(' ').append(c < line.length ? line[c] : "").append(" |");
            }
            sb.append('\n');
            if (r == 0) {
                sb.append('|');
                for (int c = 0; c < cols; c++) {
                    sb.append(" --- |");
                }
                sb.append('\n');
            }
        }
        sb.append('\n');
    }

    private static String inline(Element el) {
        StringBuilder sb = new StringBuilder();
        renderInline(el, sb);
        return sb.toString().trim();
    }

    private static void renderInline(Element el, StringBuilder sb) {
        for (Node node : el.childNodes()) {
            if (node instanceof TextNode tn) {
                sb.append(tn.text());
            } else if (node instanceof Element child) {
                switch (child.tagName()) {
                    case "strong", "b" -> sb.append("**").append(child.text()).append("**");
                    case "em", "i" -> sb.append("*").append(child.text()).append("*");
                    case "code" -> sb.append("`").append(child.text()).append("`");
                    case "a" -> {
                        String href = child.attr("href");
                        String label = child.text();
                        if (label.isBlank()) {
                            label = href;
                        }
                        sb.append("[").append(label).append("](").append(href).append(")");
                    }
                    case "br" -> sb.append('\n');
                    default -> {
                        if (isBlockTag(child.tagName())) {
                            continue; // 块级子元素由 renderBlock / renderListItem 处理
                        }
                        renderInline(child, sb);
                    }
                }
            }
        }
    }

    private static boolean isBlockTag(String tag) {
        return switch (tag) {
            case "p", "div", "section", "article", "ul", "ol", "li", "blockquote",
                 "table", "pre", "h1", "h2", "h3", "h4", "h5", "h6", "hr" -> true;
            default -> false;
        };
    }

    /** 供调用方复用：解析 XHTML 字符串为文档树。 */
    public static Document parse(String xhtml) {
        return org.jsoup.Jsoup.parse(xhtml);
    }
}
