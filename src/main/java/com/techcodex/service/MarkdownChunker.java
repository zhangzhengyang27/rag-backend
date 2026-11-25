package com.techcodex.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Markdown 结构感知分块器。
 *
 * <p>为什么不用 LangChain4j 的 {@code DocumentByRegexSplitter}：它按标题行切分后
 * 只保留正文，不记录标题路径；且 {@link com.techcodex.service.KbIndexer} 之前的
 * {@code stripMarkdown} 会把围栏代码块整体替换成空格，技术文档最关键的代码示例
 * 直接进不了检索。本类把「标题栈 + 围栏代码块」作为一等公民：</p>
 * <ul>
 *   <li>标题：按 # ~ ###### 维护层级栈，生成 headingPath（如 Java &gt; 集合 &gt; HashMap）；</li>
 *   <li>代码：``` / ~~~ 围栏内原样保留，单独成块并记录 codeLang；</li>
 *   <li>正文：按段落累积，超过 maxChars 时按句号/换行切分，保留 overlap；</li>
 *   <li>frontmatter：文件头 YAML 不进入检索正文。</li>
 * </ul>
 *
 * <p>设计边界：不解析 HTML、表格语义、setext 标题；这些留给后续解析升级。
 * 输入为空或只有 frontmatter 时返回空列表，由调用方按失败处理。</p>
 */
public final class MarkdownChunker {

    public static final String TYPE_TEXT = "text";
    public static final String TYPE_CODE = "code";
    public static final String TYPE_TABLE = "table";

    /** 句末标点后切句；中英文标点都覆盖，保留标点本身。 */
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[。！？；.!?;])");

    private MarkdownChunker() {
    }

    /**
     * 分块结果。tokenCount 为估算值（CJK 按 1 字 1 token，其他按 4 字符 1 token），
     * 只用于缓存/预算，不追求与真实 tokenizer 完全一致。
     */
    public record Chunk(String text, String headingPath, String heading, String anchor, int headingOrdinal,
                        String chunkType, String codeLang, String tableHeader, int tokenCount) {
    }

    /**
     * 将 Markdown 切成结构感知分块。
     *
     * @param markdown 原始 Markdown（可含 frontmatter）
     * @param maxChars 正文块最大字符数（建议 500）
     * @param overlap  正文块重叠字符数（建议 60）
     * @return 按文档顺序排列的分块；空文档返回空列表
     */
    public static List<Chunk> chunk(String markdown, int maxChars, int overlap) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        int max = Math.max(100, maxChars);
        int ov = Math.min(Math.max(0, overlap), max / 2);

        String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        int start = skipFrontmatter(lines);

        List<Chunk> out = new ArrayList<>();
        List<Integer> headingLevels = new ArrayList<>();
        List<String> headingTexts = new ArrayList<>();
        int headingOrdinal = 0;
        String headingPath = "";
        String heading = "";
        int currentOrdinal = 0;

        StringBuilder prose = new StringBuilder();
        StringBuilder code = new StringBuilder();
        boolean inCode = false;
        boolean inTable = false;
        char fenceChar = '`';
        String codeLang = "";
        String headingAnchor = "";
        HeadingSlugger slugger = new HeadingSlugger();

        for (int i = start; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (inCode) {
                if (isClosingFence(trimmed, fenceChar)) {
                    flushCode(code, codeLang, headingPath, heading, headingAnchor, currentOrdinal, max, out);
                    inCode = false;
                    codeLang = "";
                } else {
                    code.append(line).append('\n');
                }
                continue;
            }

            if (isOpeningFence(trimmed)) {
                flushProse(prose, headingPath, heading, headingAnchor, currentOrdinal, max, ov, inTable, out);
                inTable = false;
                inCode = true;
                fenceChar = trimmed.charAt(0);
                codeLang = fenceInfo(trimmed);
                continue;
            }

            // 表格行必须整段保留：先冲刷前面的正文，再把连续 | 行收进同一段落，
            // 避免表格被空行/句末标点切碎后丢失列语义。
            if (inTable && !isTableRow(trimmed)) {
                flushProse(prose, headingPath, heading, headingAnchor, currentOrdinal, max, ov, true, out);
                inTable = false;
            }
            if (isTableRow(trimmed)) {
                if (!inTable) {
                    flushProse(prose, headingPath, heading, headingAnchor, currentOrdinal, max, ov, false, out);
                    inTable = true;
                }
                prose.append(line).append('\n');
                continue;
            }

            if (isAtxHeading(trimmed)) {
                flushProse(prose, headingPath, heading, headingAnchor, currentOrdinal, max, ov, inTable, out);
                inTable = false;
                int level = headingLevel(trimmed);
                String text = headingText(trimmed);
                String anchor = slugger.slug(text);
                while (!headingLevels.isEmpty()
                        && headingLevels.get(headingLevels.size() - 1) >= level) {
                    headingLevels.remove(headingLevels.size() - 1);
                    headingTexts.remove(headingTexts.size() - 1);
                }
                headingLevels.add(level);
                headingTexts.add(text);
                headingOrdinal++;
                headingPath = String.join(" > ", headingTexts);
                heading = text;
                headingAnchor = anchor;
                currentOrdinal = headingOrdinal;
                continue;
            }

            // Setext 标题：正文行紧邻 ===/--- 下划线时提升为一级/二级标题，
            // 兼容老文档与手写笔记（ATX # 之外的另一种常见写法）。
            if (i + 1 < lines.length && isSetextUnderline(lines[i + 1].trim())) {
                flushProse(prose, headingPath, heading, headingAnchor, currentOrdinal, max, ov, inTable, out);
                inTable = false;
                int level = lines[i + 1].trim().charAt(0) == '=' ? 1 : 2;
                String text = trimmed;
                String anchor = slugger.slug(text);
                while (!headingLevels.isEmpty()
                        && headingLevels.get(headingLevels.size() - 1) >= level) {
                    headingLevels.remove(headingLevels.size() - 1);
                    headingTexts.remove(headingTexts.size() - 1);
                }
                headingLevels.add(level);
                headingTexts.add(text);
                headingOrdinal++;
                headingPath = String.join(" > ", headingTexts);
                heading = text;
                headingAnchor = anchor;
                currentOrdinal = headingOrdinal;
                i++; // 跳过下划线行
                continue;
            }

            if (trimmed.isEmpty()) {
                // 空行是表格结束的边界：先 flush，避免两张相邻表格被合并成一个 table 块。
                if (inTable) {
                    flushProse(prose, headingPath, heading, headingAnchor, currentOrdinal, max, ov, true, out);
                    inTable = false;
                }
                // 段落分隔保留为空行；不在这里 flush，避免短段落被切成一堆碎片
                if (prose.length() > 0 && !prose.toString().endsWith("\n\n")) {
                    prose.append("\n\n");
                }
                continue;
            }

            prose.append(line).append('\n');
        }

        // 未闭合的围栏：按代码块收尾，避免内容整体丢失
        if (inCode) {
            flushCode(code, codeLang, headingPath, heading, headingAnchor, currentOrdinal, max, out);
        }
        flushProse(prose, headingPath, heading, headingAnchor, currentOrdinal, max, ov, inTable, out);
        return out;
    }

    /** 跳过文件头 YAML frontmatter；没有合法闭合时按普通正文处理。 */
    private static int skipFrontmatter(String[] lines) {
        int first = 0;
        while (first < lines.length && lines[first].trim().isEmpty()) {
            first++;
        }
        if (first >= lines.length || !lines[first].trim().equals("---")) {
            return 0;
        }
        for (int i = first + 1; i < lines.length; i++) {
            if (lines[i].trim().equals("---") || lines[i].trim().equals("...")) {
                return i + 1;
            }
        }
        return 0;
    }

    /** 三个及以上同字符（` 或 ~）开头视为围栏；信息串允许出现在后面。 */
    private static boolean isOpeningFence(String trimmed) {
        if (trimmed.isEmpty() || (trimmed.charAt(0) != '`' && trimmed.charAt(0) != '~')) {
            return false;
        }
        int n = 0;
        while (n < trimmed.length() && trimmed.charAt(n) == trimmed.charAt(0)) {
            n++;
        }
        return n >= 3;
    }

    /** 闭合围栏：同字符、至少三个、后面只有空白（标准 CommonMark 行为）。 */
    private static boolean isClosingFence(String trimmed, char fenceChar) {
        if (trimmed.isEmpty() || trimmed.charAt(0) != fenceChar) {
            return false;
        }
        int n = 0;
        while (n < trimmed.length() && trimmed.charAt(n) == fenceChar) {
            n++;
        }
        return n >= 3 && trimmed.substring(n).trim().isEmpty();
    }

    /** 取围栏后的语言标识（只保留第一个词元，防止 ```java title=... 之类长信息串）。 */
    private static String fenceInfo(String trimmed) {
        int n = 0;
        while (n < trimmed.length() && (trimmed.charAt(n) == '`' || trimmed.charAt(n) == '~')) {
            n++;
        }
        String info = trimmed.substring(n).trim();
        if (info.isEmpty()) {
            return "";
        }
        String[] parts = info.split("\\s+");
        String lang = parts[0].replaceAll("[^A-Za-z0-9_+.-]", "");
        return lang.length() > 32 ? lang.substring(0, 32) : lang;
    }

    /** 表格行：以 | 开头即视为表格行，兼容首尾竖线不完整的写法。 */
    private static boolean isTableRow(String trimmed) {
        return trimmed.length() > 1 && trimmed.charAt(0) == '|';
    }

    /** Setext 下划线：整行只由 = 或 - 组成（至少 3 个）。 */
    private static boolean isSetextUnderline(String trimmed) {
        if (trimmed.length() < 3) {
            return false;
        }
        char c = trimmed.charAt(0);
        if (c != '=' && c != '-') {
            return false;
        }
        for (int i = 1; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) != c) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAtxHeading(String trimmed) {
        if (trimmed.isEmpty() || trimmed.charAt(0) != '#') {
            return false;
        }
        int level = headingLevel(trimmed);
        return level >= 1 && level <= 6
                && level < trimmed.length()
                && Character.isWhitespace(trimmed.charAt(level));
    }

    private static int headingLevel(String trimmed) {
        int n = 0;
        while (n < trimmed.length() && trimmed.charAt(n) == '#') {
            n++;
        }
        return n;
    }

    private static String headingText(String trimmed) {
        int n = headingLevel(trimmed);
        String text = trimmed.substring(n).trim();
        // 去掉可选的闭合 #（如 "## 标题 ##"）
        text = text.replaceAll("#+$", "").trim();
        return text;
    }

    /** 正文块：清洗行内 Markdown 后按 maxChars/overlap 切分。 */
    private static void flushProse(StringBuilder prose, String headingPath, String heading, String anchor,
                                   int ordinal, int max, int overlap, boolean table, List<Chunk> out) {
        String text = cleanInline(prose.toString()).trim();
        prose.setLength(0);
        if (text.isEmpty()) {
            return;
        }
        // 表格块保留整段（含管道符），tableHeader 取首行；正文/代码块该字段为空。
        String tableHeader = table ? firstTableHeader(text) : null;
        String type = table ? TYPE_TABLE : TYPE_TEXT;
        for (String piece : splitText(text, max, overlap)) {
            out.add(new Chunk(piece, headingPath, heading, anchor, ordinal, type, null,
                    tableHeader, estimateTokens(piece)));
        }
    }

    /** 表格首行即表头；截到 1024，防止异常超宽表撑爆列。 */
    private static String firstTableHeader(String text) {
        int newline = text.indexOf('\n');
        String header = newline >= 0 ? text.substring(0, newline) : text;
        return header.length() <= 1024 ? header : header.substring(0, 1024);
    }

    /**
     * 代码块：原样保留，单独成块。超长代码按行切分；单行超长时硬切。
     * 这样 500 字的围栏代码不会被静默丢弃，也不会把整篇文档的 embedding 撑爆。
     */
    private static void flushCode(StringBuilder code, String codeLang, String headingPath,
                                  String heading, String anchor, int ordinal, int max, List<Chunk> out) {
        String text = code.toString().stripTrailing();
        code.setLength(0);
        if (text.isBlank()) {
            return;
        }
        int limit = Math.max(200, max);
        if (text.length() <= limit) {
            addCode(out, text, codeLang, headingPath, heading, anchor, ordinal);
            return;
        }
        StringBuilder part = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (part.length() > 0 && part.length() + line.length() + 1 > limit) {
                addCode(out, part.toString().stripTrailing(), codeLang, headingPath, heading, anchor, ordinal);
                part.setLength(0);
            }
            if (line.length() > limit) {
                if (part.length() > 0) {
                    addCode(out, part.toString().stripTrailing(), codeLang, headingPath, heading, anchor, ordinal);
                    part.setLength(0);
                }
                for (int i = 0; i < line.length(); i += limit) {
                    addCode(out, line.substring(i, Math.min(line.length(), i + limit)),
                            codeLang, headingPath, heading, anchor, ordinal);
                }
            } else {
                part.append(line).append('\n');
            }
        }
        if (part.length() > 0) {
            addCode(out, part.toString().stripTrailing(), codeLang, headingPath, heading, anchor, ordinal);
        }
    }

    private static void addCode(List<Chunk> out, String text, String codeLang, String headingPath,
                                String heading, String anchor, int ordinal) {
        if (text.isBlank()) {
            return;
        }
        out.add(new Chunk(text, headingPath, heading, anchor, ordinal, TYPE_CODE,
                codeLang == null || codeLang.isBlank() ? null : codeLang, null, estimateTokens(text)));
    }

    /**
     * 标题锚点生成器：近似 github-slugger / rehype-slug 的行为，供前端 #fragment 直接跳转。
     * 只保留字母、数字、CJK、空格和连字符；空格转连字符，同标题按出现顺序加 -1/-2。
     * 与前端 extractToc 的完全一致性仍以文档页实际 slug 为准；这里生成的是稳定可用的 best-effort 锚点。
     */
    /**
     * 标题 slug 生成器。每个文档实例独立计数，保证同一文档内重复标题得到
     * {@code title}、{@code title-1}、{@code title-2} 的稳定锚点。
     */
    public static final class HeadingSlugger {
        private final Map<String, Integer> seen = new HashMap<>();

        public String slug(String raw) {
            String text = raw == null ? "" : raw;
            // 行内代码/链接/图片只保留可见文本；粗体/斜体标记去掉
            text = text.replaceAll("`([^`]*)`", "$1")
                    .replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "")
                    .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
                    .replaceAll("[*_~]+", "");
            String base = text.toLowerCase(Locale.ROOT)
                    .replaceAll("[^\\p{L}\\p{N}\\- ]", "")
                    .trim()
                    .replaceAll("\\s+", "-")
                    .replaceAll("-+", "-");
            int n = seen.merge(base, 1, Integer::sum) - 1;
            return n == 0 ? base : base + "-" + n;
        }
    }

    /**
     * 清洗行内 Markdown：只去掉对检索无意义的图片/HTML 注释，链接保留文字；
     * 不删除反引号、粗体标记，避免破坏代码术语（如 `List<String>`）。
     */
    static String cleanInline(String raw) {
        return raw
                .replaceAll("<!--[\\s\\S]*?-->", " ")
                .replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", " ")
                .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n");
    }

    /**
     * 正文切分：先按句末标点切句，再按 maxChars 装箱；单句超长时按换行/空格/硬切兜底。
     * overlap 只作用于硬切，不跨句重复，避免引用片段出现半句。
     */
    static List<String> splitText(String text, int max, int overlap) {
        List<String> pieces = new ArrayList<>();
        if (text.length() <= max) {
            pieces.add(text);
            return pieces;
        }
        String[] sentences = SENTENCE_END.split(text);
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            if (sentence.isBlank()) {
                continue;
            }
            if (current.length() + sentence.length() <= max) {
                current.append(sentence);
                continue;
            }
            if (current.length() > 0) {
                pieces.add(current.toString().trim());
                current.setLength(0);
            }
            if (sentence.length() <= max) {
                current.append(sentence);
            } else {
                pieces.addAll(hardSplit(sentence, max, overlap));
            }
        }
        if (current.length() > 0) {
            String tail = current.toString().trim();
            if (!tail.isEmpty()) {
                pieces.add(tail);
            }
        }
        return pieces;
    }

    /** 超长单句：优先在换行/空格处断开，否则按 max 硬切，带 overlap。 */
    private static List<String> hardSplit(String text, int max, int overlap) {
        List<String> pieces = new ArrayList<>();
        int len = text.length();
        int start = 0;
        while (start < len) {
            int end = Math.min(len, start + max);
            if (end < len) {
                int newline = text.lastIndexOf('\n', end);
                if (newline > start + max / 2) {
                    end = newline;
                } else {
                    int space = text.lastIndexOf(' ', end);
                    if (space > start + max / 2) {
                        end = space;
                    }
                }
            }
            String piece = text.substring(start, end).trim();
            if (!piece.isEmpty()) {
                pieces.add(piece);
            }
            int next = end - overlap;
            if (next <= start) {
                next = end;
            }
            start = next;
        }
        return pieces;
    }

    /** 轻量 token 估算：CJK/日韩文字按 1 字 1 token，其余按 4 字符 1 token。 */
    static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if ((cp >= 0x4E00 && cp <= 0x9FFF)
                    || (cp >= 0x3040 && cp <= 0x30FF)
                    || (cp >= 0xAC00 && cp <= 0xD7AF)) {
                cjk++;
            } else {
                other++;
            }
            i += Character.charCount(cp);
        }
        return Math.max(1, cjk + (int) Math.ceil(other / 4.0));
    }
}
