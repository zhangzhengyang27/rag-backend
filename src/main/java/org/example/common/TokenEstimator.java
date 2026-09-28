package org.example.common;

/**
 * 轻量 token 估算器：流式回答（AiService）不暴露真实 token 用量，
 * 用字符级启发式估算输入/输出 token 数，用于用量统计看板（标注「估算」）。
 *
 * <p>规则：中文（CJK）字符按 1 token/字、其余字符按 4 字符/token 折算；
 * 空文本为 0，非空至少计 1，避免产生 0 token 的异常记录。</p>
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        long cjk = text.codePoints()
                .filter(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)
                .count();
        long other = text.codePoints().count() - cjk;
        return (int) Math.max(1, cjk + Math.ceil(other / 4.0));
    }
}
