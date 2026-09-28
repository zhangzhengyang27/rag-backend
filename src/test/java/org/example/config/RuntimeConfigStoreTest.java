package org.example.config;

import org.example.repository.KbRuntimeConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运行时配置存储单测：批量更新的两阶段原子性（合法+非法混批整批拒绝、无部分应用）、
 * 范围校验（0~1 型阈值）、NaN 拒绝、恢复默认。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RuntimeConfigStoreTest {

    @Mock
    private KbConfig kbConfig;

    @Mock
    private KbRuntimeConfigRepository repository;

    @Mock
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private RuntimeConfigStore store;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.when(transactionManager.getTransaction(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        store = new RuntimeConfigStore(kbConfig, repository, transactionManager);
        store.load();
    }

    @Test
    void mixedBatchIsRejectedAtomically() {
        // 合法项在前、非法项在后：整批拒绝，任何键都不得落库或生效
        Map<String, String> batch = new LinkedHashMap<>();
        batch.put("retrieval.final-top-k", "9");
        batch.put("retrieval.min-score", "abc");

        assertThatThrownBy(() -> store.update(batch, "admin"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).save(any());
        assertThat(store.getInt("retrieval.final-top-k")).isNotEqualTo(9);
        assertThat(store.view().stream()
                .filter(i -> i.key().equals("retrieval.final-top-k")).findFirst().orElseThrow()
                .overridden()).isFalse();
    }

    @Test
    void scoreKeysRejectOutOfRangeValues() {
        assertThatThrownBy(() -> store.update(Map.of("eval.hitrate-threshold", "1.5"), "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.update(Map.of("retrieval.relevance-min-score", "-0.1"), "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        // NaN 绕过比较运算的经典陷阱必须被 isFinite 拦下
        assertThatThrownBy(() -> store.update(Map.of("retrieval.min-score", "NaN"), "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateThenRestoreDefault() {
        when(kbConfig.getFinalTopK()).thenReturn(6);
        store.update(Map.of("retrieval.final-top-k", "9"), "admin");
        assertThat(store.getInt("retrieval.final-top-k")).isEqualTo(9);

        // 空串 = 清除覆盖，回退默认
        store.update(Map.of("retrieval.final-top-k", ""), "admin");
        assertThat(store.getInt("retrieval.final-top-k")).isEqualTo(6);
        verify(repository, org.mockito.Mockito.atLeastOnce()).findByCfgKey("retrieval.final-top-k");
    }

    @Test
    void unknownKeyRejected() {
        assertThatThrownBy(() -> store.update(Map.of("no.such.key", "1"), "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.get("no.such.key")).isInstanceOf(IllegalArgumentException.class);
    }
}
