package com.techcodex.common;

/**
 * 流式引用编号守卫：只保留 sources 范围内的 [n]，丢弃越界编号，避免模型幻觉编号进入回答。
 *
 * <p>流式 token 可能把 `[14]` 拆成 `[`、`14`、`]` 三段，所以不能对每个 token 单独做正则替换；
 * 这里缓存尾部未闭合的 `[...`，等闭合后再判断。普通 Markdown 链接 `[文字](url)` 不是纯数字，
 * 原样输出；缓存超过 12 个字符仍无闭合时按普通文本放行，防止异常输入无限占用。</p>
 */
public final class CitationGuard {

    private final int maxCitation;
    private final StringBuilder pending = new StringBuilder();

    public CitationGuard(int maxCitation) {
        this.maxCitation = maxCitation;
    }

    public String filter(String input) {
        if (input == null || input.isEmpty()) return "";
        pending.append(input);
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < pending.length()) {
            char c = pending.charAt(i);
            if (c == '[') {
                int close = pending.indexOf("]", i + 1);
                if (close < 0) {
                    // 未闭合：等后续 token；但异常长文本不能一直缓存
                    if (pending.length() - i > 12) {
                        out.append(c);
                        i++;
                        continue;
                    }
                    break;
                }
                String inner = pending.substring(i + 1, close);
                if (inner.matches("\\d{1,3}")) {
                    int n = Integer.parseInt(inner);
                    if (n >= 1 && n <= maxCitation) {
                        out.append('[').append(n).append(']');
                    }
                    // 越界编号直接丢弃
                    i = close + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        pending.delete(0, i);
        return out.toString();
    }

    /** 流结束时把未闭合/未消费的尾部原样吐出，避免吞掉正文。 */
    public String flush() {
        String tail = pending.toString();
        pending.setLength(0);
        return tail;
    }
}
