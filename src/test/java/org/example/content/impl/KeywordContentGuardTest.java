package org.example.content.impl;

import org.example.config.KbConfig;
import org.example.config.RuntimeConfigStore;
import org.example.content.ContentGuard;
import org.example.repository.KbRuntimeConfigRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 关键词内容审核单测：命中/未命中、脱敏掩码、大小写不敏感、空词表关闭行为。
 * 词表来源改为运行时配置覆盖（管理端在线可调），classpath 词表文件仍叠加生效。
 */
class KeywordContentGuardTest {

    private ContentGuard guard(String inline, String file) {
        KbConfig kbConfig = Mockito.mock(KbConfig.class);
        KbRuntimeConfigRepository repo = Mockito.mock(KbRuntimeConfigRepository.class);
        var txManager = Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class);
        Mockito.when(txManager.getTransaction(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        RuntimeConfigStore store = new RuntimeConfigStore(kbConfig, repo, txManager);
        store.update(Map.of("safety.enabled", "true", "safety.sensitive-words", inline), "test");
        return new KeywordContentGuard(store, file);
    }

    @Test
    void detectsViolationWord() {
        ContentGuard g = guard("敏感词A,敏感词B", "nonexistent.txt");
        assertThat(g.isEnabled()).isTrue();
        assertThat(g.findViolation("这段文本包含敏感词A")).contains("敏感词a");
        assertThat(g.findViolation("正常的技术文档内容")).isEmpty();
    }

    @Test
    void matchingIsCaseInsensitive() {
        ContentGuard g = guard("test,word", "nonexistent.txt");
        assertThat(g.findViolation("This is a TEST message")).contains("test");
        assertThat(g.findViolation("no sensitive content")).isEmpty();
    }

    @Test
    void sanitizeMasksMatchedWords() {
        ContentGuard g = guard("secret,密令", "nonexistent.txt");
        String out = g.sanitize("请输入你的 secret 密码并执行密令");
        assertThat(out).contains("******").doesNotContain("secret");
        assertThat(out).contains("**").doesNotContain("密令");
        // 未命中内容保持不变
        assertThat(g.sanitize("普通技术问答")).isEqualTo("普通技术问答");
    }

    @Test
    void emptyWordListDisablesGuard() {
        ContentGuard g = guard("", "nonexistent.txt");
        assertThat(g.isEnabled()).isFalse();
        assertThat(g.findViolation("任意内容")).isEmpty();
        assertThat(g.sanitize("任意内容")).isEqualTo("任意内容");
    }

    @Test
    void loadsWordsFromResourceFile() {
        ContentGuard g = guard("", "sensitive-words.txt");
        // 资源文件含「演示敏感词A」
        assertThat(g.isEnabled()).isTrue();
        assertThat(g.findViolation("这是演示敏感词A示例")).isPresent();
        assertThat(g.sanitize("包含演示敏感词A的文本")).doesNotContain("演示敏感词A");
    }
}
