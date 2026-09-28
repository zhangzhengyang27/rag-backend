package org.example.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * token 估算器单测：边界（空串）与单调性、中文计 1 字/token、非中文按 4 字符折算。
 */
class TokenEstimatorTest {

    @Test
    void emptyInputYieldsZero() {
        assertThat(TokenEstimator.estimate(null)).isZero();
        assertThat(TokenEstimator.estimate("")).isZero();
    }

    @Test
    void nonEmptyInputAtLeastOne() {
        assertThat(TokenEstimator.estimate("a")).isEqualTo(1);
        assertThat(TokenEstimator.estimate("好")).isEqualTo(1);
    }

    @Test
    void cjkCountsOneTokenPerChar() {
        // 4 个汉字 → 4 token
        assertThat(TokenEstimator.estimate("知识库问答")).isEqualTo(5);
    }

    @Test
    void latinCountsFourCharsPerToken() {
        // 4 个英文字符 → 1 token
        assertThat(TokenEstimator.estimate("abcd")).isEqualTo(1);
        // 5 个英文字符 → 向上取整 = 2
        assertThat(TokenEstimator.estimate("abcde")).isEqualTo(2);
    }

    @Test
    void mixedTextEstimatesBothParts() {
        // 2 汉字 + 8 个拉丁字符 = 2 + 2 = 4
        assertThat(TokenEstimator.estimate("你好hello")).isEqualTo(4);
    }

    @Test
    void estimateIsMonotonicNonDecreasing() {
        String base = "知识库检索与问答系统";
        String longer = base + "，并支持多空间权限管理";
        assertThat(TokenEstimator.estimate(longer))
                .isGreaterThanOrEqualTo(TokenEstimator.estimate(base));
    }
}
