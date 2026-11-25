package com.techcodex.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 领域同义词种子表：只解决“自然问法用了一个说法，文档标题/正文用了另一个说法”的窄问题。
 *
 * <p>为什么需要它：标题词法通道按词面召回，遇到“代理机制 → AOP”“约束冲突 → 约束”时
 * 没有交集；这不是向量能稳定补上的，也不值得为三条用例上全量 NER。这里用可审计的
 * 短语级别名，把问句里的业务说法映射回知识库里的技术词。</p>
 *
 * <p>边界：别名只在查询完整包含左侧短语时触发，不做单字匹配；不替代查询分解、标题通道
 * 或实体关系，只作为高精度补充。新增别名应来自真实问法，不能拿评测题反推。</p>
 */
public final class KbConceptAliases {

    /** 左侧是自然问法，右侧是知识库常见标题/正文词；保持短小，避免把普通词扩成噪声。 */
    private static final Map<String, List<String>> ALIASES;
    static {
        Map<String, List<String>> rules = new LinkedHashMap<>();
        rules.put("代理机制", List.of("代理", "AOP", "AOP 原理"));
        rules.put("约束冲突", List.of("约束", "冲突"));
        rules.put("全表扫描", List.of("索引", "执行计划"));
        rules.put("索引存在", List.of("索引"));
        ALIASES = Collections.unmodifiableMap(rules);
    }

    private KbConceptAliases() {
    }

    /** 返回查询命中的别名，按固定顺序去重；未命中返回空列表。 */
    public static List<String> expand(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Set<String> expanded = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> entry : ALIASES.entrySet()) {
            if (query.contains(entry.getKey())) {
                expanded.addAll(entry.getValue());
            }
        }
        return new ArrayList<>(expanded);
    }
}
