package com.techcodex.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CMS Markdown 的 frontmatter 处理：只做「首尾 --- 块」的结构拆分与单行标量改写，
 * 不引入 YAML 全量解析，避免 dump 时把原有缩进、注释和数组格式全部重排。
 *
 * <p>边界：frontmatter 只识别首行 `---` 到下一行 `---` 的块；正文里的 `---` 分隔线不受影响。
 * 标题/摘要等标量值统一写成双引号字符串，并转义反斜杠与双引号。</p>
 */
public final class CmsMarkdown {

    private static final Pattern FIRST_HEADING = Pattern.compile("(?m)^\\s*#{1,6}\\s+(.+?)\\s*$");

    private CmsMarkdown() {
    }

    /** 拆分结果：frontmatter 含首尾分隔线（末尾补 \n），body 为剩余正文。 */
    public record Parts(String frontmatter, String body) {
    }

    public static Parts split(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new Parts("", "");
        }
        String text = normalizeNewlines(raw);
        String[] lines = text.split("\n", -1);
        if (lines.length == 0 || !"---".equals(lines[0].trim())) {
            return new Parts("", text);
        }
        int offset = lines[0].length() + 1;
        for (int i = 1; i < lines.length; i++) {
            if ("---".equals(lines[i].trim())) {
                int end = offset + lines[i].length();
                String fm = text.substring(0, end) + "\n";
                int bodyStart = Math.min(text.length(), end + 1);
                return new Parts(fm, text.substring(bodyStart));
            }
            offset += lines[i].length() + 1;
        }
        // 只有开头没有闭合：不猜测结构，整体当正文，避免把正文误写成元数据
        return new Parts("", text);
    }

    public static String merge(String frontmatter, String body) {
        String fm = frontmatter == null ? "" : normalizeNewlines(frontmatter).trim();
        String text = body == null ? "" : normalizeNewlines(body);
        if (fm.isEmpty()) {
            return text;
        }
        if (!fm.endsWith("\n")) {
            fm += "\n";
        }
        return fm + text;
    }

    /** 读取 frontmatter 的单行标量；不存在返回 null。 */
    public static String getScalar(String frontmatter, String key) {
        if (frontmatter == null || key == null || key.isBlank()) {
            return null;
        }
        String[] lines = normalizeNewlines(frontmatter).split("\n", -1);
        String prefix = key + ":";
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.startsWith(prefix)) {
                continue;
            }
            String value = trimmed.substring(prefix.length()).trim();
            if (value.isEmpty()) {
                return null;
            }
            return unquote(value);
        }
        return null;
    }

    /**
     * 写入/覆盖 frontmatter 的单行标量；不存在则插入到闭合分隔线前。
     * value 为 null 时删除该行；空字符串写成 `key: ""`。
     */
    public static String setScalar(String frontmatter, String key, String value) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("frontmatter key 不能为空");
        }
        String fm = frontmatter == null ? "" : normalizeNewlines(frontmatter).trim();
        if (fm.isEmpty() || !fm.startsWith("---")) {
            fm = "---\n---";
        }
        String[] lines = fm.split("\n", -1);
        int close = -1;
        for (int i = lines.length - 1; i >= 1; i--) {
            if ("---".equals(lines[i].trim())) {
                close = i;
                break;
            }
        }
        if (close < 0) {
            throw new IllegalArgumentException("frontmatter 缺少闭合分隔线");
        }

        String rendered = value == null ? null : key + ": " + quote(value);
        int found = -1;
        for (int i = 1; i < close; i++) {
            if (lines[i].trim().startsWith(key + ":")) {
                found = i;
                break;
            }
        }

        List<String> next = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (i == found) {
                if (rendered != null) {
                    next.add(rendered);
                }
                continue;
            }
            if (i == close && found < 0 && rendered != null) {
                next.add(rendered);
            }
            next.add(lines[i]);
        }
        return String.join("\n", next).trim() + "\n";
    }

    /** 从完整 Markdown 解析标题：frontmatter.title → 第一个标题 → fileName。 */
    public static String resolveTitle(String raw, String fileName) {
        Parts parts = split(raw);
        String title = getScalar(parts.frontmatter(), "title");
        if (title != null && !title.isBlank()) {
            return title;
        }
        Matcher matcher = FIRST_HEADING.matcher(parts.body());
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        if (fileName != null && !fileName.isBlank()) {
            return fileName.replaceFirst("(?i)\\.[a-z0-9]+$", "");
        }
        return "未命名文档";
    }

    public static String resolveDescription(String raw) {
        return getScalar(split(raw).frontmatter(), "description");
    }

    /** 新建文档的默认 frontmatter：只写标题/摘要，其余标签由用户后续在正文里维护。 */
    public static String buildFrontmatter(String title, String description) {
        StringBuilder sb = new StringBuilder("---\n");
        sb.append("title: ").append(quote(title == null ? "" : title)).append('\n');
        if (description != null && !description.isBlank()) {
            sb.append("description: ").append(quote(description)).append('\n');
        }
        sb.append("---\n");
        return sb.toString();
    }

    public static String normalizeNewlines(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static String unquote(String value) {
        String v = value.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        } else if (v.length() >= 2 && v.startsWith("'") && v.endsWith("'")) {
            v = v.substring(1, v.length() - 1);
        }
        return v.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
