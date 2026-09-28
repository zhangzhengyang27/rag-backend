package org.example.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.document.splitter.DocumentByRegexSplitter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.example.entity.KbDocumentStatus;
import org.example.entity.KnowledgeChunkEntity;
import org.example.entity.KnowledgeDocumentEntity;
import org.example.repository.KnowledgeChunkRepository;
import org.example.repository.KnowledgeDocumentRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库文档索引器：负责将 Markdown 正文清洗、段落分块、向量化并写入专有向量索引，
 * 同时将分块持久化到 {@code kb_chunk} 供关键词检索与溯源使用。
 * <p>流程：正文 → {@link Document} → {@link DocumentByParagraphSplitter} 分块 → 向量化 → Redis 存储 + 分块落库。</p>
 */
@Slf4j
@Component
public class KbIndexer {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> kbEmbeddingStore;
    private final KnowledgeChunkRepository chunkRepository;
    private final KnowledgeDocumentRepository documentRepository;
    private final TransactionTemplate transactionTemplate;
    private final int maxSegmentSize;
    private final int maxOverlapSize;

    public KbIndexer(EmbeddingModel embeddingModel,
                     EmbeddingStore<TextSegment> kbEmbeddingStore,
                     KnowledgeChunkRepository chunkRepository,
                     KnowledgeDocumentRepository documentRepository,
                     PlatformTransactionManager transactionManager,
                     org.example.config.KbConfig kbConfig) {
        this.embeddingModel = embeddingModel;
        this.kbEmbeddingStore = kbEmbeddingStore;
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.maxSegmentSize = kbConfig.getChunkMaxSegmentSize();
        this.maxOverlapSize = kbConfig.getChunkMaxOverlapSize();
    }

    /**
     * 对单个文档执行索引：分块 → 向量化 → 写 Redis 与 kb_chunk，并更新文档索引状态。
     * 传入的文档实体若未持久化会先落库以获取主键；若已存在则作为归并对象更新。
     *
     * <p>失败顺序约束（防止索引失败破坏既有数据）：
     * 切分/向量化全部成功后才允许删除旧分块与旧向量——更新文档时若切分失败，
     * 旧数据原样保留（文档保持可检索），由调用方按异常计为失败；DB 写入收敛到
     * 末尾的单个事务。已知残余窗口：Redis 非事务存储，极端情况下需用"重建索引"修复，
     * 向量以 Redis 为准、分块以 DB 为准，二者可由 vectorId 关联对账。</p>
     */
    public void indexDocument(KnowledgeDocumentEntity doc, String markdown) {
        // 1. 清洗 Markdown 语法（保留标题行，供结构感知切分识别层级）——失败不触碰任何旧数据
        String clean = stripMarkdown(markdown);
        Document document = Document.from(clean);
        // 结构感知切分：先按 Markdown 标题（# ~ ######）切成"标题块"，
        // 再通过显式 subSplitter（段落切分）将超长标题块细切到 maxSegmentSize，
        // 使每个片段保留所属标题上下文，语义更完整。
        // 注意：必须显式传入 subSplitter，否则标题块超过 maxSegmentSize 时
        // DocumentByRegexSplitter 无法再细分，会抛"no subSplitter defined"异常。
        DocumentSplitter splitter = new DocumentByRegexSplitter(
                "(?m)^#{1,6}\\s.*$", "\n\n", maxSegmentSize, maxOverlapSize,
                new DocumentByParagraphSplitter(maxSegmentSize, maxOverlapSize));
        // 切分失败/为空：抛出而非静默跳过——调用方按失败处理，已索引文档保持原状
        List<TextSegment> segments;
        try {
            segments = splitter.split(document);
        } catch (Exception e) {
            log.warn("文档切分失败，保持原文档不变 - docId: {}, title: {}, err: {}",
                    doc.getId(), doc.getTitle(), e.getMessage());
            throw new IllegalStateException("文档切分失败: " + e.getMessage(), e);
        }
        if (segments == null || segments.isEmpty()) {
            log.warn("文档切分结果为空，保持原文档不变 - docId: {}, title: {}", doc.getId(), doc.getTitle());
            throw new IllegalStateException("文档切分结果为空，无法索引");
        }

        // 新文档先落库获取主键：后续分块与 Redis 元数据都依赖 docId
        if (doc.getId() == null) {
            documentRepository.save(doc);
        }

        // 2. 构建分块实体（暂存，待向量化拿到 vectorId 后统一落库）
        List<KnowledgeChunkEntity> chunks = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            TextSegment seg = segments.get(i);
            KnowledgeChunkEntity chunk = new KnowledgeChunkEntity();
            chunk.setLibraryId(doc.getLibraryId());
            chunk.setDocId(doc.getId());
            chunk.setChunkIndex(i);
            chunk.setText(seg.text());
            chunk.setCharCount(seg.text().length());
            chunks.add(chunk);
        }

        // 3. 向量化（网络调用，事务外执行，避免长事务占用连接）
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();

        // 4. 落库前先按 vectorId 精准删除该文档的 Redis 旧向量（归并更新场景）
        if (doc.getId() != null) {
            java.util.List<String> oldVectorIds = chunkRepository.findVectorIdsByDocId(doc.getId());
            if (oldVectorIds != null && !oldVectorIds.isEmpty()) {
                try {
                    kbEmbeddingStore.removeAll(oldVectorIds);
                } catch (Exception e) {
                    log.warn("删除文档旧向量失败 - docId: {}, err: {}", doc.getId(), e.getMessage());
                }
            }
        }

        // 5. 向量写入专有索引（带溯源元数据），拿到各分块的 vectorId 用于后续精准删除
        List<TextSegment> metaSegments = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            Map<String, Comparable<?>> meta = new HashMap<>();
            meta.put("docId", doc.getId());
            meta.put("title", doc.getTitle() == null ? "" : doc.getTitle());
            meta.put("filePath", doc.getFilePath() == null ? "" : doc.getFilePath());
            meta.put("siteSlug", doc.getSiteSlug() == null ? "" : doc.getSiteSlug());
            meta.put("category", doc.getCategory() == null ? "" : doc.getCategory());
            meta.put("chunkIndex", i);
            metaSegments.add(TextSegment.from(segments.get(i).text(), Metadata.from(meta)));
        }
        List<String> vectorIds = kbEmbeddingStore.addAll(embeddings, metaSegments);
        if (vectorIds != null) {
            for (int i = 0; i < Math.min(vectorIds.size(), chunks.size()); i++) {
                chunks.get(i).setVectorId(vectorIds.get(i));
            }
        }

        // 6. DB 收敛到单个事务：清旧分块（归并更新）+ 写新分块 + 更新文档状态，避免中间态
        transactionTemplate.executeWithoutResult(status -> {
            if (doc.getId() != null) {
                // 归并更新：清理旧分块（旧 Redis 向量已在上方删除）
                chunkRepository.deleteByDocId(doc.getId());
            }
            chunkRepository.saveAll(chunks);
            doc.setChunkCount(segments.size());
            doc.setStatus(KbDocumentStatus.INDEXED);
            documentRepository.save(doc);
        });
        log.info("知识库文档已索引 - id: {}, title: {}, chunks: {}", doc.getId(), doc.getTitle(), segments.size());
    }

    private String stripMarkdown(String markdown) {
        if (markdown == null) {
            return "";
        }
        String text = markdown
                .replaceAll("```[\\s\\S]*?```", " ")                       // 代码块
                .replaceAll("<!--[\\s\\S]*?-->", " ")                       // HTML 注释
                .replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", " ")               // 图片
                .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")             // 链接（保留链接文字）
                // 注意：标题行（# ~ ######）与换行刻意保留，供 KbIndexer 的结构感知切分按层级切分；
                // 若把换行折叠成单空格，整篇文档会变成一行，标题正则会一次性吞掉全文导致切分为空。
                .replaceAll("[`\\*_~>]", " ")                               // 行内格式标记
                .replaceAll("(?m)^\\s*[-*+]\\s+", "")                        // 无序列表标记（逐行）
                .replaceAll("\\|", " ")
                // 只折叠行内横向空白，保留换行（标题/段落结构依赖换行）
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n")                              // 折叠多余空行为段落分隔
                .trim();
        return text;
    }
}
