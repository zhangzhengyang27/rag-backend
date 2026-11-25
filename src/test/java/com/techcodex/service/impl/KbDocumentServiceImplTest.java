package com.techcodex.service.impl;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import com.techcodex.config.KbConfig;
import com.techcodex.entity.KbDocumentStatus;
import com.techcodex.entity.KnowledgeDocumentEntity;
import com.techcodex.parse.DocumentParser;
import com.techcodex.parse.impl.UrlFetcher;
import com.techcodex.repository.KnowledgeChunkRepository;
import com.techcodex.repository.KnowledgeDocumentRepository;
import com.techcodex.service.KbIndexer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文档管理服务空间化单测：get 限定空间、upload 带空间去重。
 */
@ExtendWith(MockitoExtension.class)
class KbDocumentServiceImplTest {

    @Mock
    private KnowledgeDocumentRepository documentRepository;
    @Mock
    private KnowledgeChunkRepository chunkRepository;
    @Mock
    private KbIndexer kbIndexer;
    @Mock
    private EmbeddingStore<TextSegment> kbEmbeddingStore;
    @Mock
    private Executor kbImportExecutor;
    @Mock
    private KbConfig kbConfig;
    @Mock
    private com.techcodex.quota.QuotaService quotaService;
    @Mock
    private DocumentParser documentParser;
    @Mock
    private UrlFetcher urlFetcher;
    @Mock
    private com.techcodex.service.AuditLogService auditLogService;
    @Mock
    private com.techcodex.service.KbAlertService kbAlertService;
    @Mock
    private com.techcodex.service.DocumentInfoCache docCache;
    @Mock
    private redis.clients.jedis.JedisPooled kbJedis;
    @Mock
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private KbDocumentServiceImpl service;

    @BeforeEach
    void setUp() {
        // 构造器含 @Value 基本类型（int maxFileSize），Mockito @InjectMocks 无法注入，手动构造
        service = new KbDocumentServiceImpl(documentRepository, chunkRepository, kbIndexer,
                kbEmbeddingStore, kbImportExecutor, kbConfig, quotaService,
                documentParser, urlFetcher, auditLogService, kbAlertService, docCache,
                kbJedis, "lostsystem:kbdoc:", transactionManager, 10_485_760);
    }

    @Test
    void getScopesToLibrary() {
        when(documentRepository.findByIdAndLibraryId(1L, "acme"))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(1L, "acme", false))
                .isInstanceOf(IllegalArgumentException.class);
        verify(documentRepository).findByIdAndLibraryId(1L, "acme");
    }

    @Test
    void uploadSetsLibraryId() {
        when(documentRepository.existsByContentHashAndLibraryId(anyString(), anyString())).thenReturn(false);
        when(documentRepository.save(any(KnowledgeDocumentEntity.class))).thenAnswer(inv -> {
            KnowledgeDocumentEntity e = inv.getArgument(0);
            e.setId(9L);
            e.setStatus(KbDocumentStatus.INDEXED);
            return e;
        });

        org.springframework.mock.web.MockMultipartFile file =
                new org.springframework.mock.web.MockMultipartFile("file", "a.md", "text/markdown",
                        "# Hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        service.upload(file, null, "alice", "acme");

        verify(documentRepository).existsByContentHashAndLibraryId(anyString(), anyString());
    }

    @Test
    void uploadStoresStructuredPath() {
        when(documentRepository.existsByContentHashAndLibraryId(anyString(), anyString())).thenReturn(false);
        when(documentRepository.save(any(KnowledgeDocumentEntity.class))).thenAnswer(inv -> {
            KnowledgeDocumentEntity e = inv.getArgument(0);
            e.setId(11L);
            e.setStatus(KbDocumentStatus.INDEXED);
            return e;
        });

        org.springframework.mock.web.MockMultipartFile file =
                new org.springframework.mock.web.MockMultipartFile("file", "笔记.md", "text/markdown",
                        "# 上传".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // 个人库 id 是 u-{username}，上传人应取后缀而不是 userId
        service.upload(file, null, "alice", "u-alice");

        org.mockito.ArgumentCaptor<KnowledgeDocumentEntity> captor =
                org.mockito.ArgumentCaptor.forClass(KnowledgeDocumentEntity.class);
        verify(documentRepository).save(captor.capture());
        KnowledgeDocumentEntity saved = captor.getValue();

        // upload/{上传人}/{8 位 uuid}-{原名}
        assertThat(saved.getFilePath()).matches("upload/alice/[0-9a-f]{8}-笔记\\.md");
        // 原始文件名单独保留，用于列表展示
        assertThat(saved.getFileName()).isEqualTo("笔记.md");
        // 上传文档没有分类目录，不能落到 "upload" 这个伪空间
        assertThat(saved.getSpace()).isEqualTo("default");
    }

    @Test
    void uploadStripsPathPrefixAndTraversal() {
        when(documentRepository.existsByContentHashAndLibraryId(anyString(), anyString())).thenReturn(false);
        when(documentRepository.save(any(KnowledgeDocumentEntity.class))).thenAnswer(inv -> {
            KnowledgeDocumentEntity e = inv.getArgument(0);
            e.setId(12L);
            e.setStatus(KbDocumentStatus.INDEXED);
            return e;
        });

        // 浏览器在部分平台会给出带盘符与路径的文件名，这里同时验证 basename 与 ".." 剥离
        org.springframework.mock.web.MockMultipartFile file =
                new org.springframework.mock.web.MockMultipartFile("file", "C:\\fakepath\\..\\evil.md",
                        "text/markdown", "# x".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        service.upload(file, null, "bob", "u-bob");

        org.mockito.ArgumentCaptor<KnowledgeDocumentEntity> captor =
                org.mockito.ArgumentCaptor.forClass(KnowledgeDocumentEntity.class);
        verify(documentRepository).save(captor.capture());
        String path = captor.getValue().getFilePath();

        assertThat(path).startsWith("upload/bob/");
        assertThat(path).doesNotContain("..");
        assertThat(path).doesNotContain("fakepath");
        assertThat(path).endsWith("-evil.md");
    }

    @Test
    void uploadDelegatesBinaryToDocumentParser() {
        when(documentRepository.existsByContentHashAndLibraryId(anyString(), anyString())).thenReturn(false);
        when(documentParser.parse(any(byte[].class), eq("b.docx"))).thenReturn("# 标题\n\n解析出的正文");
        when(documentRepository.save(any(KnowledgeDocumentEntity.class))).thenAnswer(inv -> {
            KnowledgeDocumentEntity e = inv.getArgument(0);
            e.setId(10L);
            e.setStatus(KbDocumentStatus.INDEXED);
            return e;
        });

        org.springframework.mock.web.MockMultipartFile file =
                new org.springframework.mock.web.MockMultipartFile("file", "b.docx", "application/octet-stream",
                        new byte[]{1, 2, 3, 4});

        service.upload(file, null, "alice", "acme");

        verify(documentParser).parse(any(byte[].class), eq("b.docx"));
        org.mockito.ArgumentCaptor<KnowledgeDocumentEntity> captor =
                org.mockito.ArgumentCaptor.forClass(KnowledgeDocumentEntity.class);
        verify(documentRepository).save(captor.capture());
        assertThat(captor.getValue().getContent()).isEqualTo("# 标题\n\n解析出的正文");
    }

    @Test
    void uploadPlainMarkdownSkipsDocumentParser() {
        when(documentRepository.existsByContentHashAndLibraryId(anyString(), anyString())).thenReturn(false);
        when(documentRepository.save(any(KnowledgeDocumentEntity.class))).thenAnswer(inv -> {
            KnowledgeDocumentEntity e = inv.getArgument(0);
            e.setId(11L);
            e.setStatus(KbDocumentStatus.INDEXED);
            return e;
        });

        org.springframework.mock.web.MockMultipartFile file =
                new org.springframework.mock.web.MockMultipartFile("file", "a.md", "text/markdown",
                        "# Hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        service.upload(file, null, "alice", "acme");

        verify(documentParser, never()).parse(any(), anyString());
    }

    @Test
    void uploadRejectsUnsupportedExtension() {
        org.springframework.mock.web.MockMultipartFile file =
                new org.springframework.mock.web.MockMultipartFile("file", "run.exe", "application/octet-stream",
                        new byte[]{1, 2, 3});

        assertThatThrownBy(() -> service.upload(file, null, "alice", "acme"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的文件类型");
    }

    @Test
    void importUrlFetchesAndIndexes() {
        when(urlFetcher.fetch("https://example.com/doc"))
                .thenReturn(new UrlFetcher.FetchedPage("页面标题", "# 页面标题\n\n抓取到的正文"));
        when(documentRepository.existsByContentHashAndLibraryId(anyString(), anyString())).thenReturn(false);
        when(documentRepository.save(any(KnowledgeDocumentEntity.class))).thenAnswer(inv -> {
            KnowledgeDocumentEntity e = inv.getArgument(0);
            e.setId(20L);
            e.setStatus(KbDocumentStatus.INDEXED);
            return e;
        });

        service.importUrl("https://example.com/doc", "alice", "acme");

        verify(quotaService).assertDocumentQuota("acme");
        verify(urlFetcher).fetch("https://example.com/doc");
        org.mockito.ArgumentCaptor<KnowledgeDocumentEntity> captor =
                org.mockito.ArgumentCaptor.forClass(KnowledgeDocumentEntity.class);
        verify(documentRepository).save(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("页面标题");
        assertThat(captor.getValue().getContent()).contains("抓取到的正文");
        assertThat(captor.getValue().getFilePath()).isEqualTo("https://example.com/doc");
        assertThat(captor.getValue().getLibraryId()).isEqualTo("acme");
        verify(kbIndexer).indexDocument(captor.getValue(), "# 页面标题\n\n抓取到的正文");
    }

    @Test
    void reindexMarksFailedWhenIndexingThrows() {
        KnowledgeDocumentEntity doc = new KnowledgeDocumentEntity();
        doc.setId(11L);
        doc.setTitle("坏文档");
        doc.setContentHash("h1");
        doc.setStatus(KbDocumentStatus.INDEXED);
        when(documentRepository.findByIdAndLibraryId(11L, "acme")).thenReturn(Optional.of(doc));
        when(documentRepository.save(any(KnowledgeDocumentEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0)); // merge 语义：返回受管副本
        org.mockito.Mockito.doThrow(new IllegalStateException("embedding boom"))
                .when(kbIndexer).indexDocument(any(KnowledgeDocumentEntity.class), anyString());

        assertThatThrownBy(() -> service.reindex(11L, "acme"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重建索引失败");

        // 失败后文档必须被标记 FAILED 落库，而非停留在 PENDING/旧状态
        org.mockito.ArgumentCaptor<KnowledgeDocumentEntity> captor =
                org.mockito.ArgumentCaptor.forClass(KnowledgeDocumentEntity.class);
        org.mockito.Mockito.verify(documentRepository, org.mockito.Mockito.atLeastOnce())
                .save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(KbDocumentStatus.FAILED);
    }
}
