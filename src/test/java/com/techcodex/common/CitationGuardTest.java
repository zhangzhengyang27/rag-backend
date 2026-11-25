package com.techcodex.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CitationGuardTest {

    @Test
    void keepsValidAndDropsInvalid() {
        CitationGuard guard = new CitationGuard(3);
        assertThat(guard.filter("答案[1]，另一个[9]。")).isEqualTo("答案[1]，另一个。");
        assertThat(guard.flush()).isEmpty();
    }

    @Test
    void handlesCitationSplitAcrossTokens() {
        CitationGuard guard = new CitationGuard(14);
        assertThat(guard.filter("见[1")).isEqualTo("见");
        assertThat(guard.filter("4]")).isEqualTo("[14]");
        assertThat(guard.filter("继续")).isEqualTo("继续");
        assertThat(guard.flush()).isEmpty();
    }

    @Test
    void leavesNormalMarkdownLinkAlone() {
        CitationGuard guard = new CitationGuard(3);
        assertThat(guard.filter("参考[文档](https://x)")).isEqualTo("参考[文档](https://x)");
        assertThat(guard.flush()).isEmpty();
    }

    @Test
    void flushReturnsUnclosedTail() {
        CitationGuard guard = new CitationGuard(3);
        assertThat(guard.filter("结尾[")).isEqualTo("结尾");
        assertThat(guard.flush()).isEqualTo("[");
    }
}
