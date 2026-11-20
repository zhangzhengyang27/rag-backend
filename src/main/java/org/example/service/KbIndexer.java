package org.example.service;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.example.entity.KbDocumentStatus;
import org.example.entity.KnowledgeChunkEntity;
import org.example.entity.KnowledgeDocumentEntity;
import org.example.entity.KnowledgeSectionEntity;
import org.example.repository.KnowledgeChunkRepository;
import org.example.repository.KnowledgeDocumentRepository;
import org.example.repository.KnowledgeSectionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库文档索引器：负责将 Markdown 正文按结构分块、向量化并写入专有向量索引，
 * 同时将分块持久化到 {@code kb_chunk} 供关键词检索与溯源使用。
 *
 * <p>分块交给 {@link MarkdownChunker}：标题路径与围栏代码块都是一等公民。
 * 这里只负责编排「切分 → 向量化 → 删旧向量 → 写新向量 → 单事务落库」，
 * 不再用 {@code stripMarkdown} 把代码块替换成空格。</p>
 */
@Slf4j
@Component
public class KbIndexer {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> kbEmbeddingStore;
    private final KnowledgeChunkRepository chunkRepository;
    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeSectionRepository sectionRepository;
    private final TransactionTemplate transactionTemplate;
    private final int maxSegmentSize;
    private final int maxOverlapSize;

    public KbIndexer(EmbeddingModel embeddingModel,
                     EmbeddingStore<TextSegment> kbEmbeddingStore,
                     KnowledgeChunkRepository chunkRepository,
                     KnowledgeDocumentRepository documentRepository,
                     KnowledgeSectionRepository sectionRepository,
                     PlatformTransactionManager transactionManager,
                     org.example.config.KbConfig kbConfig) {
        this.embeddingModel = embeddingModel;
        this.kbEmbeddingStore = kbEmbeddingStore;
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
        this.sectionRepository = sectionRepository;
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
        // 1. 结构感知分块（保留代码块、标题路径、标题序号）；失败不触碰任何旧数据
        List<MarkdownChunker.Chunk> chunks = MarkdownChunker.chunk(markdown, maxSegmentSize, maxOverlapSize);
        if (chunks.isEmpty()) {
            log.warn("文档分块结果为空，保持原文档不变 - docId: {}, title: {}", doc.getId(), doc.getTitle());
            throw new IllegalStateException("文档切分结果为空，无法索引");
        }

        // 新文档先落库获取主键：后续分块与 Redis 元数据都依赖 docId
        if (doc.getId() == null) {
            documentRepository.save(doc);
        }

        // 1.5 先按标题序号聚合父块文本：子块负责精准召回，父块只作章节上下文，不建向量。
        Map<Integer, SectionDraft> sections = new java.util.LinkedHashMap<>();
        for (MarkdownChunker.Chunk chunk : chunks) {
            sections.computeIfAbsent(chunk.headingOrdinal(), key -> new SectionDraft(chunk))
                    .append(chunk);
        }

        // 2. 构建分块实体与向量 segment（暂存，待向量化拿到 vectorId 后统一落库）
        List<KnowledgeChunkEntity> entities = new ArrayList<>(chunks.size());
        List<TextSegment> segments = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            MarkdownChunker.Chunk chunk = chunks.get(i);
            // DB 列宽约束：标题路径 512、标题 255、语言 32；超长标题按列宽截断，
            // 避免一条超长 heading 让整篇文档索引失败（Redis metadata 同步用截断值）
            String headingPath = truncate(chunk.headingPath(), 512);
            String heading = truncate(chunk.heading(), 255);
            String anchor = truncate(chunk.anchor(), 1024);
            String codeLang = truncate(chunk.codeLang(), 32);
            KnowledgeChunkEntity entity = new KnowledgeChunkEntity();
            entity.setLibraryId(doc.getLibraryId());
            entity.setDocId(doc.getId());
            entity.setChunkIndex(i);
            entity.setText(chunk.text());
            entity.setCharCount(chunk.text().length());
            entity.setHeadingPath(headingPath);
            // 后端生成近似 github-slugger 的锚点；前端仍会在缺失/旧索引时按 heading 兜底
            entity.setAnchor(anchor == null ? "" : anchor);
            entity.setHeadingOrdinal(chunk.headingOrdinal());
            entity.setChunkType(chunk.chunkType());
            entity.setCodeLang(codeLang);
            entity.setTableHeader(chunk.tableHeader());
            entity.setTokenCount(chunk.tokenCount());
            entities.add(entity);

            Map<String, Comparable<?>> meta = new HashMap<>();
            meta.put("docId", doc.getId());
            meta.put("chunkIndex", i);
            meta.put("title", doc.getTitle() == null ? "" : doc.getTitle());
            meta.put("filePath", doc.getFilePath() == null ? "" : doc.getFilePath());
            meta.put("siteSlug", doc.getSiteSlug() == null ? "" : doc.getSiteSlug());
            meta.put("category", doc.getCategory() == null ? "" : doc.getCategory());
            meta.put("headingPath", headingPath == null ? "" : headingPath);
            meta.put("heading", heading == null ? "" : heading);
            meta.put("anchor", anchor == null ? "" : anchor);
            meta.put("headingOrdinal", chunk.headingOrdinal());
            meta.put("chunkType", chunk.chunkType());
            meta.put("codeLang", codeLang == null ? "" : codeLang);
            meta.put("tableHeader", chunk.tableHeader() == null ? "" : chunk.tableHeader());
            meta.put("tokenCount", chunk.tokenCount());
            segments.add(TextSegment.from(chunk.text(), Metadata.from(meta)));
        }

        // 3. 向量化（网络调用，事务外执行，避免长事务占用连接）
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();

        // 4. 落库前先按 vectorId 精准删除该文档的 Redis 旧向量（归并更新场景）
        if (doc.getId() != null) {
            List<String> oldVectorIds = chunkRepository.findVectorIdsByDocId(doc.getId());
            if (oldVectorIds != null && !oldVectorIds.isEmpty()) {
                try {
                    kbEmbeddingStore.removeAll(oldVectorIds);
                } catch (Exception e) {
                    log.warn("删除文档旧向量失败 - docId: {}, err: {}", doc.getId(), e.getMessage());
                }
            }
        }

        // 5. 向量写入专有索引（带溯源与结构元数据），拿到各分块的 vectorId 用于后续精准删除
        List<String> vectorIds = kbEmbeddingStore.addAll(embeddings, segments);
        if (vectorIds != null) {
            for (int i = 0; i < Math.min(vectorIds.size(), entities.size()); i++) {
                entities.get(i).setVectorId(vectorIds.get(i));
            }
        }

        // 6. DB 收敛到单个事务：清旧分块（归并更新）+ 写新分块 + 更新文档状态，避免中间态
        transactionTemplate.executeWithoutResult(status -> {
            if (doc.getId() != null) {
                // 归并更新：清理旧分块/旧 section（旧 Redis 向量已在上方删除）
                chunkRepository.deleteByDocId(doc.getId());
                sectionRepository.deleteByDocId(doc.getId());
            }
            // 父块先落库拿 ID，再给子块回填 parent_id；父块不写向量索引。
            List<KnowledgeSectionEntity> sectionEntities = new ArrayList<>(sections.size());
            for (Map.Entry<Integer, SectionDraft> entry : sections.entrySet()) {
                SectionDraft draft = entry.getValue();
                KnowledgeSectionEntity section = new KnowledgeSectionEntity();
                section.setLibraryId(doc.getLibraryId());
                section.setDocId(doc.getId());
                section.setHeadingOrdinal(entry.getKey());
                section.setHeadingPath(truncate(draft.headingPath, 512));
                section.setAnchor(truncate(draft.anchor, 1024));
                section.setText(truncate(draft.text.toString(), 20_000));
                section.setChunkCount(draft.chunkCount);
                section.setTokenCount(draft.tokenCount);
                sectionEntities.add(section);
            }
            sectionRepository.saveAll(sectionEntities);
            sectionRepository.flush();
            Map<Integer, Long> sectionIds = new HashMap<>();
            for (KnowledgeSectionEntity section : sectionEntities) {
                sectionIds.put(section.getHeadingOrdinal(), section.getId());
            }
            for (KnowledgeChunkEntity entity : entities) {
                entity.setParentId(sectionIds.get(entity.getHeadingOrdinal()));
            }
            chunkRepository.saveAll(entities);
            doc.setChunkCount(chunks.size());
            doc.setStatus(KbDocumentStatus.INDEXED);
            documentRepository.save(doc);
        });
        log.info("知识库文档已索引 - id: {}, title: {}, chunks: {}, codeChunks: {}",
                doc.getId(), doc.getTitle(), chunks.size(),
                chunks.stream().filter(c -> MarkdownChunker.TYPE_CODE.equals(c.chunkType())).count());
    }

    /** 章节草稿：只聚合子块文本与元数据，避免为父块再跑一次解析。 */
    private static final class SectionDraft {
        private final StringBuilder text = new StringBuilder();
        private final String headingPath;
        private final String anchor;
        private int chunkCount;
        private int tokenCount;

        private SectionDraft(MarkdownChunker.Chunk first) {
            this.headingPath = first.headingPath();
            this.anchor = first.anchor();
        }

        private void append(MarkdownChunker.Chunk chunk) {
            if (text.length() < 20_000) {
                text.append(chunk.text()).append('\n');
            }
            chunkCount++;
            tokenCount += chunk.tokenCount();
        }
    }

    /** 按 DB 列宽截断，null 保持 null（用于标题路径/标题/代码语言）。 */
    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
