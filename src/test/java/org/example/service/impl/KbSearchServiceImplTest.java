package org.example.service.impl;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.example.config.KbConfig;
import org.example.config.RuntimeConfigStore;
import org.example.dto.KbChunkHit;
import org.example.repository.KbRuntimeConfigRepository;
import org.example.repository.KnowledgeChunkRepository;
import org.example.repository.KnowledgeDocumentRepository;
import org.example.repository.RetrievalTraceRepository;
import org.example.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检索空间隔离单测：验证 scopedFilterDocIds 的空间/路径/空间交集逻辑。
 */
@ExtendWith(MockitoExtension.class)
class KbSearchServiceImplTest {

    @Mock
    private EmbeddingModel embeddingModel;
    @Mock
    private EmbeddingStore<TextSegment> kbEmbeddingStore;
    @Mock
    private KnowledgeChunkRepository chunkRepository;
    @Mock
    private KnowledgeDocumentRepository documentRepository;
    @Mock
    private RetrievalTraceRepository retrievalTraceRepository;
    @Mock
    private KbConfig kbConfig;
    @Mock
    private KbRuntimeConfigRepository runtimeConfigRepository;
    @Mock
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @InjectMocks
    private KbSearchServiceImpl service;

    @BeforeEach
    void setUpRuntimeConfig() {
        // @InjectMocks 无法构造 RuntimeConfigStore（非 mock 类型），这里注入真实存储：
        // 默认值来自 kbConfig mock（未打桩返回 0/false），不影响空间过滤逻辑断言
        ReflectionTestUtils.setField(service, "runtimeConfig",
                new RuntimeConfigStore(kbConfig, runtimeConfigRepository, transactionManager));
    }

    @Test
    void scopedFilterIntersectsLibraryWithPathAndSpace() {
        when(documentRepository.findDocIdsByLibrary("default")).thenReturn(Set.of(1L, 2L, 3L, 4L));
        when(documentRepository.findDocIdsByLibraryAndFilePathPrefix("default", "前端/")).thenReturn(Set.of(1L, 2L));
        when(documentRepository.findDocIdsByLibraryAndSpace("default", "前端")).thenReturn(Set.of(2L, 3L));

        Set<Long> ids = service.scopedFilterDocIds("default", "前端/", "前端");

        assertThat(ids).containsExactly(2L);
    }

    @Test
    void scopedFilterReturnsEmptyWhenLibraryHasNoDocs() {
        when(documentRepository.findDocIdsByLibrary("default")).thenReturn(Set.of());
        assertThat(service.scopedFilterDocIds("default", null, null)).isEmpty();
    }

    @Test
    void scopedFilterReturnsEmptyWhenPathHasNoMatch() {
        when(documentRepository.findDocIdsByLibrary("default")).thenReturn(Set.of(1L, 2L));
        when(documentRepository.findDocIdsByLibraryAndFilePathPrefix("default", "后端/")).thenReturn(Set.of());
        assertThat(service.scopedFilterDocIds("default", "后端/", null)).isEmpty();
    }

    @Test
    void scopedFilterIgnoresBlankPathAndSpace() {
        when(documentRepository.findDocIdsByLibrary("acme")).thenReturn(Set.of(7L, 8L));
        assertThat(service.scopedFilterDocIds("acme", "  ", "")).containsExactlyInAnyOrder(7L, 8L);
    }

    @Test
    void searchMultiPropagatesCallerLibraryToAsyncSubqueries() {
        // 模拟登录态在调用线程：异步子查询必须使用这里捕获的空间，而不是回落默认空间
        when(kbConfig.getPublicLibrary()).thenReturn("default");
        when(documentRepository.findDocIdsByLibrary("acme")).thenReturn(Set.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser("alice", "USER", "acme"), null, List.of()));
        try {
            List<KbChunkHit> hits = service.searchMulti(List.of("什么是A", "什么是B"), 5);
            assertThat(hits).isEmpty();
        } finally {
            SecurityContextHolder.clearContext();
        }
        verify(documentRepository, atLeastOnce()).findDocIdsByLibrary("acme");
        verify(documentRepository, never()).findDocIdsByLibrary("default");
        verify(kbEmbeddingStore, never()).search(any(EmbeddingSearchRequest.class));
    }

    @Test
    void searchAsUsesExplicitLibraryInsteadOfSecurityContext() {
        // 无登录态时，显式传入的空间仍须生效（异步/工具线程场景）
        when(documentRepository.findDocIdsByLibrary("acme")).thenReturn(Set.of());
        List<KbChunkHit> hits = service.searchAs("acme", "alice", "任意问题", 5, null, null, null);
        assertThat(hits).isEmpty();
        verify(documentRepository).findDocIdsByLibrary("acme");
        verify(kbEmbeddingStore, never()).search(any(EmbeddingSearchRequest.class));
    }

    /* ---------- 多空间并集（检索范围 scope=all） ---------- */

    @Test
    void scopedFilterMultiUnionsLibrarys() {
        when(documentRepository.findDocIdsByLibrary("default")).thenReturn(Set.of(1L, 2L));
        when(documentRepository.findDocIdsByLibrary("u-alice")).thenReturn(Set.of(3L));

        Set<Long> ids = service.scopedFilterDocIdsMulti(List.of("default", "u-alice"), "alice", null, null);

        assertThat(ids).containsExactlyInAnyOrder(1L, 2L, 3L);
    }


    @Test
    void scopedFilterMultiAppliesPathFilterPerLibrary() {
        when(documentRepository.findDocIdsByLibrary("default")).thenReturn(Set.of(1L, 2L));
        when(documentRepository.findDocIdsByLibrary("u-alice")).thenReturn(Set.of(3L));
        when(documentRepository.findDocIdsByLibraryAndFilePathPrefix("default", "前端/")).thenReturn(Set.of(2L));
        when(documentRepository.findDocIdsByLibraryAndFilePathPrefix("u-alice", "前端/")).thenReturn(Set.of());

        Set<Long> ids = service.scopedFilterDocIdsMulti(List.of("default", "u-alice"), "alice", "前端/", null);

        // 目录过滤按空间独立计算后并集，个人空间无匹配不阻断公共空间命中
        assertThat(ids).containsExactly(2L);
    }

    @Test
    void scopedFilterMultiEmptyWhenAllLibrarysEmpty() {
        when(documentRepository.findDocIdsByLibrary("empty1")).thenReturn(Set.of());
        when(documentRepository.findDocIdsByLibrary("empty2")).thenReturn(Set.of());

        Set<Long> ids = service.scopedFilterDocIdsMulti(List.of("empty1", "empty2"), "alice", null, null);

        assertThat(ids).isEmpty();
    }
}
