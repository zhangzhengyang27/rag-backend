package org.example.quota;

import org.example.config.KbConfig;
import org.example.config.RuntimeConfigStore;
import org.example.exception.QuotaExceededException;
import org.example.quota.impl.InMemoryQuotaDailyCounter;
import org.example.quota.impl.QuotaServiceImpl;
import org.example.repository.KbRuntimeConfigRepository;
import org.example.repository.KnowledgeDocumentRepository;
import org.example.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 配额单测：个人空间按配置限额（文档/检索/问答），公共空间不限量，usage 只读。
 * 用户级限额覆盖（管理端调整）另有集成测试覆盖；此处用户均不存在 → 走运行时配置默认限额
 * （RuntimeConfigStore 未加载 DB 覆盖时回退 KbConfig 默认值，即取对 KbConfig 的打桩值）。
 */
@ExtendWith(MockitoExtension.class)
class QuotaServiceImplTest {

    @Mock
    private KbConfig kbConfig;

    @Mock
    private KbRuntimeConfigRepository runtimeConfigRepository;

    @Mock
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Mock
    private KnowledgeDocumentRepository documentRepository;

    @Mock
    private UserRepository userRepository;

    private QuotaServiceImpl service;

    @BeforeEach
    void setUp() {
        // update() 之外的本类用例不触发事务，lenient 避免严格模式下未用打桩报错
        org.mockito.Mockito.lenient().when(transactionManager.getTransaction(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        RuntimeConfigStore store = new RuntimeConfigStore(kbConfig, runtimeConfigRepository, transactionManager);
        service = new QuotaServiceImpl(store, documentRepository, new InMemoryQuotaDailyCounter(), userRepository);
    }

    @Test
    void personalSpaceBlocksDocumentOverLimit() {
        when(kbConfig.getSelfServiceDocLimit()).thenReturn(2L);
        when(documentRepository.countByLibraryId("u-dave")).thenReturn(2L);

        assertThatThrownBy(() -> service.assertDocumentQuota("u-dave"))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("私有知识库");
    }

    @Test
    void personalSpaceAllowsWithinDocumentLimit() {
        when(kbConfig.getSelfServiceDocLimit()).thenReturn(10L);
        when(documentRepository.countByLibraryId("u-dave")).thenReturn(2L);

        assertThatCode(() -> service.assertDocumentQuota("u-dave")).doesNotThrowAnyException();
    }

    @Test
    void publicSpaceIsUnlimited() {
        // 公共空间（default）不限量：导入与检索/问答都不校验、不拦截
        // （文档数打桩省略——publicLimit 前置短路，根本不会去数文档）
        when(kbConfig.getSelfServiceDocLimit()).thenReturn(2L);
        assertThatCode(() -> service.assertDocumentQuota("default")).doesNotThrowAnyException();
        assertThatCode(() -> service.consumeRetrieval("default")).doesNotThrowAnyException();
        assertThatCode(() -> service.consumeChat("default")).doesNotThrowAnyException();
    }

    @Test
    void personalSpaceBlocksRetrievalOverDailyLimit() {
        when(kbConfig.getSelfServiceRetrievalLimit()).thenReturn(2L);

        service.consumeRetrieval("u-dave");
        service.consumeRetrieval("u-dave");
        assertThatThrownBy(() -> service.consumeRetrieval("u-dave"))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void zeroLimitMeansUnlimited() {
        when(kbConfig.getSelfServiceRetrievalLimit()).thenReturn(0L);
        assertThatCode(() -> service.consumeRetrieval("u-dave")).doesNotThrowAnyException();
    }

    @Test
    void usageReportsDocCountAndDailyPeekWithoutConsuming() {
        when(kbConfig.getSelfServiceDocLimit()).thenReturn(200L);
        when(kbConfig.getSelfServiceRetrievalLimit()).thenReturn(200L);
        when(kbConfig.getSelfServiceChatLimit()).thenReturn(100L);
        when(documentRepository.countByLibraryId("u-dave")).thenReturn(3L);

        service.consumeRetrieval("u-dave");
        service.consumeRetrieval("u-dave");
        service.consumeChat("u-dave");

        QuotaUsage usage = service.usage("u-dave");
        org.assertj.core.api.Assertions.assertThat(usage.docUsed()).isEqualTo(3L);
        org.assertj.core.api.Assertions.assertThat(usage.docLimit()).isEqualTo(200L);
        org.assertj.core.api.Assertions.assertThat(usage.retrievalUsed()).isEqualTo(2L);
        org.assertj.core.api.Assertions.assertThat(usage.chatUsed()).isEqualTo(1L);
        // usage 只读：再查一次数值不变
        assertThatCode(() -> service.usage("u-dave")).doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThat(service.usage("u-dave").retrievalUsed()).isEqualTo(2L);
    }
}
