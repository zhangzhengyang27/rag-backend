package org.example.content;

import java.util.Optional;

/**
 * 内容安全审核组件：对输入（用户问题）做敏感词/合规拦截，对输出（回答文本）做脱敏。
 * 当前为关键词层实现，后续可替换/叠加模型审核。
 */
public interface ContentGuard {

    /** 是否启用审核（词表为空或开关关闭时返回 false，不拦截不脱敏）。 */
    boolean isEnabled();

    /**
     * 检查文本是否命中敏感词。
     *
     * @return 首个命中的敏感词（未命中返回空）
     */
    Optional<String> findViolation(String text);

    /**
     * 将文本中的敏感词替换为等长掩码（*），返回脱敏后的文本。
     */
    String sanitize(String text);
}
