package org.example.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 别名表是窄规则，必须锁住“命中完整短语才扩、无短语不扩”的边界。 */
class KbConceptAliasesTest {

    @Test
    void expandsProxyMechanismToAop() {
        assertEquals(List.of("代理", "AOP", "AOP 原理"),
                KbConceptAliases.expand("Spring 声明式事务为什么会失效？代理机制和事务传播行为之间是什么关系？"));
    }

    @Test
    void expandsConstraintConflict() {
        assertEquals(List.of("约束", "冲突"),
                KbConceptAliases.expand("遇到约束冲突时怎么处理？"));
    }

    @Test
    void deduplicatesIndexAliasFromTwoPhrases() {
        assertEquals(List.of("索引", "执行计划"),
                KbConceptAliases.expand("如果索引存在却仍走全表扫描，应该怎么排查？"));
    }

    @Test
    void keepsEmptyWhenNoAliasPhrase() {
        assertEquals(List.of(), KbConceptAliases.expand("MySQL 执行计划里 type=ALL 代表什么？"));
    }
}
