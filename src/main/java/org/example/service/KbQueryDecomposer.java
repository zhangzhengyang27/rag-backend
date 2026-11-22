package org.example.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 轻量多跳查询分解：只做确定性的“问句/并列概念”切分，不调用 LLM。
 *
 * <p>为什么需要它：自然问法多跳常把两个实体塞进一句长问句（“Webpack 和 Vite 的构建流程有什么区别？”），
 * 单路向量会被整体语义带向其中一个文档。拆成子查询分别召回，再在文档级做 RRF，
 * 可以让两个概念各自拿到候选；这比上全量 GraphRAG 轻，也比简单并集更能保住双文档覆盖。</p>
 *
 * <p>边界：只切问号/分号/换行和“和/与”，且要求两侧都足够长；不切技术词内部的符号
 * （async/await、Cache-Control），避免把实体本身拆坏。最多返回 3 条（原句 + 2 个子查询），
 * 控制额外检索开销，保证 P95 仍可压在 1 秒内。</p>
 */
public final class KbQueryDecomposer {

    private static final int MAX_SUB_QUERIES = 3;

    private KbQueryDecomposer() {
    }

    public static List<String> decompose(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String normalized = query.replace('？', '?').replace('；', ';').replace('！', '!');
        List<String> parts = split(normalized, "[?;!\\n]");
        if (parts.size() == 1) {
            // “和/与”不会出现在技术词内部（async/await、Cache-Control 里没有汉字），可安全切分；
            // 切完仍要求每段长度 >= 6，避免把“MVCC 与事务”切出过短的“事务”。
            if (normalized.contains("和")) {
                parts = split(normalized, "和");
            } else if (normalized.contains("与")) {
                parts = split(normalized, "与");
            } else {
                // 只有一个句末问号时，split 得到的是“去掉问号的原句”，不是新信息需求；
                // 若把它当作子查询会重复检索同一句话，白付一次 embed/BM25 成本。
                return List.of(query.trim());
            }
        }
        Set<String> unique = new LinkedHashSet<>();
        unique.add(query.trim());
        for (String part : parts) {
            String cleaned = part.trim().replaceAll("^[，,、\\s]+|[，,、\\s]+$", "");
            if (cleaned.length() >= 6 && !cleaned.equals(query.trim())) {
                unique.add(cleaned);
            }
            if (unique.size() >= MAX_SUB_QUERIES) {
                break;
            }
        }
        return new ArrayList<>(unique);
    }

    private static List<String> split(String text, String regex) {
        List<String> parts = new ArrayList<>();
        for (String part : text.split(regex)) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts;
    }
}
