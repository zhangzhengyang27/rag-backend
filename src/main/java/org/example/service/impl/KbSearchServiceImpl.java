package org.example.service.impl;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.example.config.KbConfig;
import org.example.dto.KbChunkHit;
import org.example.dto.SearchOptions;
import org.example.entity.KnowledgeChunkEntity;
import org.example.entity.RetrievalTraceEntity;
import org.example.entity.KnowledgeDocumentEntity;
import org.example.repository.KnowledgeChunkRepository;
import org.example.repository.KnowledgeDocumentRepository;
import org.example.repository.RetrievalTraceRepository;
import org.example.service.KbSearchService;
import org.example.service.RrfFuser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 知识库混合检索实现：
 * <ul>
 *   <li>向量检索：专有 Redis 索引，召回语义相近分块（带 docId/title/filePath 元数据）；</li>
 *   <li>关键词检索：kb_chunk 正文 LIKE，补充向量可能漏掉的精确命中。</li>
 * </ul>
 * 结果以「向量优先、关键词补位」合并去重，附带溯源信息供回答引用。
 */
@Slf4j
@Service
public class KbSearchServiceImpl implements KbSearchService {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> kbEmbeddingStore;
    private final KnowledgeChunkRepository chunkRepository;
    private final KnowledgeDocumentRepository documentRepository;
    private final RetrievalTraceRepository retrievalTraceRepository;
    private final KbConfig kbConfig;
    /** 运行时配置存储：检索参数可在线调整，每次检索读取生效值（覆盖优先，回退启动默认）。 */
    private final org.example.config.RuntimeConfigStore runtimeConfig;
    /** 文档信息本地缓存（有界 LRU，文档更新/删除时由服务层失效），减少混合检索时的重复查询。 */
    private final org.example.service.DocumentInfoCache docCache;
    /** RediSearch BM25 关键词检索（中文分词索引），keyword-backend=redis 时启用 */
    private final org.example.search.RedisBm25KeywordSearch redisBm25KeywordSearch;
    private final boolean redisKeywordSearchEnabled;

    /**
     * 语义重排模型（本地 bge-reranker INT8 ONNX）。可为 null：
     * 未启用或模型加载失败时不注入，检索降级为纯 RRF 融合结果。
     */
    @Autowired(required = false)
    private ScoringModel rerankScoringModel;

    /** 关键词路每个词条命中的基础置信度分（多个词条命中同一分块时累加） */
    private static final double KEYWORD_MATCH_SCORE = 1.0;
    /** 召回来源路标识：向量召回 */
    private static final String RESULT_SOURCE_VECTOR = "vector";
    /** 召回来源路标识：关键词召回 */
    private static final String RESULT_SOURCE_KEYWORD = "keyword";

    public KbSearchServiceImpl(EmbeddingModel embeddingModel,
                               EmbeddingStore<TextSegment> kbEmbeddingStore,
                               KnowledgeChunkRepository chunkRepository,
                               KnowledgeDocumentRepository documentRepository,
                               RetrievalTraceRepository retrievalTraceRepository,
                               KbConfig kbConfig,
                               org.example.config.RuntimeConfigStore runtimeConfig,
                               org.example.service.DocumentInfoCache docCache,
                               org.example.search.RedisBm25KeywordSearch redisBm25KeywordSearch,
                               @Value("${app.kb.retrieval.keyword-backend:mysql}") String keywordBackend) {
        this.embeddingModel = embeddingModel;
        this.kbEmbeddingStore = kbEmbeddingStore;
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
        this.retrievalTraceRepository = retrievalTraceRepository;
        this.kbConfig = kbConfig;
        this.runtimeConfig = runtimeConfig;
        this.docCache = docCache;
        this.redisBm25KeywordSearch = redisBm25KeywordSearch;
        this.redisKeywordSearchEnabled = "redis".equalsIgnoreCase(keywordBackend);
    }

    @Override
    public List<KbChunkHit> search(String query, int topK) {
        return search(query, topK, null, null, null);
    }

    @Override
    public List<KbChunkHit> search(String query, int topK, SearchOptions options) {
        return search(query, topK, null, null, options);
    }

    @Override
    public List<KbChunkHit> search(String query, int topK, String pathFilter) {
        return search(query, topK, pathFilter, null, null);
    }

    @Override
    public List<KbChunkHit> search(String query, int topK, String pathFilter, String space) {
        return search(query, topK, pathFilter, space, null);
    }

    /** 带参数覆盖的完整检索入口（pathFilter/space 可空；options 为 null 或字段为 null 时沿用全局配置）。 */
    public List<KbChunkHit> search(String query, int topK, String pathFilter, String space, SearchOptions options) {
        // 身份必须在调用线程解析后显式传入：本方法可能被带到无登录态的线程执行（异步/流式工具线程），
        // ThreadLocal 的 SecurityContext 在那些线程上不可见，读不到就会错误回落默认空间。
        return searchAs(org.example.security.SecurityUtil.currentLibraryId(kbConfig.getPublicLibrary()),
                org.example.security.SecurityUtil.currentUsername(), query, topK, pathFilter, space, options);
    }

    /**
     * 显式身份检索：空间与用户名由调用方在持有登录态的线程上解析后传入。
     * 供 {@link #searchMulti} 的异步子查询、以及任何脱离请求线程的检索复用，
     * 避免跨空间检索与 ACL 绕过。
     */
    @Override
    public List<KbChunkHit> searchAs(String libraryId, String username, String query, int topK,
                                     String pathFilter, String space, SearchOptions options) {
        return searchAs(java.util.List.of(libraryId), username, query, topK, pathFilter, space, null, options);
    }

    /**
     * 多空间显式身份检索：逐空间计算「范围过滤 ∩ ACL 白名单」后并集合并，作为本次检索的
     * 可见 docId 集合。任一空间计算异常仅跳过该空间，不中断检索。
     */
    @Override
    public List<KbChunkHit> searchAs(List<String> libraryIds, String username, String query, int topK,
                                     String pathFilter, String space, SearchOptions options) {
        return searchAs(libraryIds, username, query, topK, pathFilter, space, null, options);
    }

    /**
     * 多空间显式身份检索（含课程分类过滤）：category 非空时仅命中该分类文档（kb_document.category）。
     */
    @Override
    public List<KbChunkHit> searchAs(List<String> libraryIds, String username, String query, int topK,
                                     String pathFilter, String space, String category, SearchOptions options) {
        long startNanos = System.nanoTime();
        // 参数覆盖（A/B 评测）：options 非空且字段非 null 时覆盖全局配置，否则沿用运行时配置生效值
        int k = options != null && options.getTopK() != null ? Math.max(1, options.getTopK()) : Math.max(1, topK);
        boolean bm25Enabled = options != null && options.getBm25Enabled() != null
                ? options.getBm25Enabled()
                : runtimeConfig.getBool("retrieval.bm25-enabled");
        boolean rerankEnabled = options != null && options.getRerankEnabled() != null
                ? options.getRerankEnabled()
                : runtimeConfig.getBool("retrieval.rerank-enabled");
        double minScore = runtimeConfig.getDouble("retrieval.min-score");
        int vectorTopN = runtimeConfig.getInt("retrieval.vector-top-n");
        int bm25TopN = runtimeConfig.getInt("retrieval.bm25-top-n");
        int finalTopK = Math.min(k, runtimeConfig.getInt("retrieval.final-top-k"));
        // 过滤条件：逐空间「空间 ∩ 目录前缀 ∩ space ∩ 分类 ∩ ACL」后并集（都转成 docId 集合，供向量/关键词双路过滤）
        final java.util.Set<Long> filterDocIds = scopedFilterDocIdsMulti(libraryIds, username, pathFilter, space, category);
        if (filterDocIds.isEmpty()) {
            return java.util.Collections.emptyList();
        }

        // 1. 向量召回（先宽后精，召回数远大于最终返回数，交给 RRF 融合）
        //    注：Redis 向量存储不支持 NumericField 的 in/contains 过滤（抛 UnsupportedOperationException），
        //    因此目录过滤改为在召回后按 docId 集合在代码内过滤，而非依赖存储层 filter。
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(embeddingModel.embed(query).content())
                .maxResults(vectorTopN)
                .minScore(minScore)
                .build();
        EmbeddingSearchResult<TextSegment> result = kbEmbeddingStore.search(request);

        List<KbChunkHit> vectorHits = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> match : result.matches()) {
            TextSegment segment = match.embedded();
            // 元数据可能为数字（Redis JSON 反序列化），getString 会抛异常，
            // 统一走 toMap 安全取值，再解析为 String/数值。
            Map<String, Object> meta = segment.metadata().toMap();
            Long id = toLong(meta.get("docId"));
            Integer chunkIndex = toInteger(meta.get("chunkIndex"));
            if (id == null || chunkIndex == null) {
                continue;
            }
            // 目录过滤（若有）：跳过不属于目标文档集合的命中
            if (filterDocIds != null && !filterDocIds.contains(id)) {
                continue;
            }
            vectorHits.add(KbChunkHit.builder()
                    .docId(id)
                    .chunkIndex(chunkIndex)
                    .title(defaultEmpty(toStringVal(meta.get("title"))))
                    .filePath(defaultEmpty(toStringVal(meta.get("filePath"))))
                    .siteSlug(defaultEmpty(toStringVal(meta.get("siteSlug"))))
                    .text(segment.text())
                    .score(match.score())
                    .source(RESULT_SOURCE_VECTOR)
                    .build());
        }

        // 2. 关键词召回（MySQL 全文/模糊，对中文技术文档术语友好）
        List<KbChunkHit> keywordHits = keywordRetrieve(libraryIds, query, bm25TopN);
        if (filterDocIds != null) {
            keywordHits.removeIf(h -> !filterDocIds.contains(h.getDocId()));
        }

        // 3. RRF 加权融合（向量 + 关键词两路），而非简单补位
        List<KbChunkHit> merged;
        if (bm25Enabled && !keywordHits.isEmpty()) {
            merged = RrfFuser.fuse(List.of(vectorHits, keywordHits), runtimeConfig.getInt("retrieval.rrf-k"), finalTopK);
        } else {
            // 关键词通道关闭时退回纯向量召回，截断到 finalTopK
            merged = vectorHits.size() > finalTopK ? vectorHits.subList(0, finalTopK) : vectorHits;
        }

        // 4. 语义重排精排（可选）：用本地 bge-reranker 对 RRF 融合后的 topN 再排序
        boolean rerankModelAvailable = rerankScoringModel != null && rerankEnabled;
        merged = rerank(merged, query, runtimeConfig.getInt("retrieval.rerank-top-n"), finalTopK, rerankEnabled);

        log.info("知识库混合检索(RRF+重排)完成 - query: {}, 向量: {}, 关键词: {}, 融合: {}, 重排生效: {}",
                query, vectorHits.size(), keywordHits.size(), merged.size(), rerankModelAvailable);
        recordTrace(truncateLibrary(joinLibrarys(libraryIds)), query, pathFilter, vectorHits.size(), keywordHits.size(), merged.size(),
                rerankModelAvailable, rerankModelAvailable, startNanos);
        return merged;
    }

    /** 多空间身份串（trace 落库用），如 "default,u-alice"。 */
    private String joinLibrarys(List<String> libraryIds) {
        return libraryIds == null ? "" : String.join(",", libraryIds);
    }

    /** trace.library_id 列宽 64，多空间串超长时截断（仅影响观测字段，不影响检索）。 */
    private String truncateLibrary(String library) {
        return library.length() <= 64 ? library : library.substring(0, 64);
    }

    @Override
    public List<KbChunkHit> searchMulti(List<String> queries, int topK) {
        return searchMulti(queries, topK, null);
    }

    @Override
    public List<KbChunkHit> searchMulti(List<String> queries, int topK, String pathFilter) {
        // 身份必须在调用线程（持有登录态）先解析：supplyAsync 落在公共 ForkJoinPool 上，
        // SecurityContext 是 ThreadLocal，异步线程读不到，直接在子查询里取会错误回落默认空间
        // 并绕过 ACL（跨空间泄露）。这里解析一次后显式传给每个子查询。
        final String libraryId = org.example.security.SecurityUtil.currentLibraryId(kbConfig.getPublicLibrary());
        final String username = org.example.security.SecurityUtil.currentUsername();
        return searchMultiAs(java.util.List.of(libraryId), username, queries, topK, pathFilter);
    }

    @Override
    public List<KbChunkHit> searchMultiAs(List<String> libraryIds, String username, List<String> queries,
                                          int topK, String pathFilter) {
        return searchMultiAs(libraryIds, username, queries, topK, pathFilter, null);
    }

    @Override
    public List<KbChunkHit> searchMultiAs(List<String> libraryIds, String username, List<String> queries,
                                          int topK, String pathFilter, String category) {
        if (queries == null || queries.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        // 并行检索各子查询，提升召回覆盖率。
        // 任一路检索异常只丢弃该路结果，不影响其余子查询与主链路（不向调用方抛 500）。
        List<CompletableFuture<List<KbChunkHit>>> futures = queries.stream()
                .map(q -> CompletableFuture.supplyAsync(() -> searchAs(libraryIds, username, q, topK, pathFilter, null, category, null)))
                .collect(Collectors.toList());
        List<List<KbChunkHit>> batches = new ArrayList<>();
        for (CompletableFuture<List<KbChunkHit>> f : futures) {
            try {
                batches.add(f.join());
            } catch (Exception e) {
                log.warn("多路检索单路失败已跳过 - err: {}", e.getMessage());
            }
        }
        // 按 docId#chunkIndex 去重合并，保持子查询顺序
        java.util.LinkedHashMap<String, KbChunkHit> seen = new java.util.LinkedHashMap<>();
        for (List<KbChunkHit> batch : batches) {
            for (KbChunkHit h : batch) {
                seen.putIfAbsent(h.getDocId() + "#" + h.getChunkIndex(), h);
            }
        }
        return new ArrayList<>(seen.values());
    }

    /**
     * 按空间 + 目录前缀 + space + 课程分类取交集得到检索可见的 docId 集合；无任何可见文档时返回空集合。
     * 供检索主链路与单测直接调用。
     */
    java.util.Set<Long> scopedFilterDocIds(String libraryId, String pathFilter, String space) {
        return scopedFilterDocIds(libraryId, pathFilter, space, null);
    }

    java.util.Set<Long> scopedFilterDocIds(String libraryId, String pathFilter, String space, String category) {
        java.util.Set<Long> libraryIds = documentRepository.findDocIdsByLibrary(libraryId);
        if (libraryIds == null || libraryIds.isEmpty()) {
            return java.util.Collections.emptySet();
        }
        java.util.Set<Long> pathIds = (pathFilter != null && !pathFilter.isBlank())
                ? documentRepository.findDocIdsByLibraryAndFilePathPrefix(libraryId, pathFilter.trim())
                : null;
        java.util.Set<Long> spaceIds = (space != null && !space.isBlank())
                ? documentRepository.findDocIdsByLibraryAndSpace(libraryId, space.trim())
                : null;
        java.util.Set<Long> categoryIds = (category != null && !category.isBlank())
                ? documentRepository.findDocIdsByLibraryAndCategory(libraryId, category.trim())
                : null;
        if ((pathIds != null && pathIds.isEmpty()) || (spaceIds != null && spaceIds.isEmpty())
                || (categoryIds != null && categoryIds.isEmpty())) {
            return java.util.Collections.emptySet();
        }
        java.util.Set<Long> result = new java.util.HashSet<>(libraryIds);
        if (pathIds != null) {
            result.retainAll(pathIds);
        }
        if (spaceIds != null) {
            result.retainAll(spaceIds);
        }
        if (categoryIds != null) {
            result.retainAll(categoryIds);
        }
        return result;
    }

    /**
     * 多空间版本的范围过滤：逐空间「空间 ∩ 目录前缀 ∩ space ∩ 分类」后并集。
     * 任一空间无可见文档只跳过该空间，不影响其余空间；供检索主链路与单测直接调用。
     */
    java.util.Set<Long> scopedFilterDocIdsMulti(java.util.List<String> libraryIds, String username,
                                                String pathFilter, String space) {
        return scopedFilterDocIdsMulti(libraryIds, username, pathFilter, space, null);
    }

    java.util.Set<Long> scopedFilterDocIdsMulti(java.util.List<String> libraryIds, String username,
                                                String pathFilter, String space, String category) {
        java.util.Set<Long> result = new java.util.HashSet<>();
        if (libraryIds == null) {
            return result;
        }
        for (String t : libraryIds) {
            if (t == null || t.isBlank()) {
                continue;
            }
            result.addAll(scopedFilterDocIds(t, pathFilter, space, category));
        }
        return result;
    }

    /** 轻量同步记录检索 Trace（单条 INSERT，失败仅记日志，不影响检索主链路），便于观测与调优。 */
    private void recordTrace(String libraryId, String query, String pathFilter, int vectorCount, int keywordCount,
                             int resultCount, boolean reranked, boolean rerankModelAvailable, long startNanos) {
        try {
            RetrievalTraceEntity trace = new RetrievalTraceEntity();
            trace.setLibraryId(libraryId);
            trace.setUserId(currentUserId());
            trace.setOriginalQuery(query);
            trace.setSearchQuery(query);
            trace.setKeywordUsed(keywordCount > 0);
            trace.setVectorCount(vectorCount);
            trace.setKeywordCount(keywordCount);
            trace.setFusedCount(resultCount);
            trace.setReranked(reranked);
            trace.setRerankModelAvailable(rerankModelAvailable);
            trace.setResultCount(resultCount);
            trace.setElapsedMs((System.nanoTime() - startNanos) / 1_000_000);
            trace.setPathFilter(pathFilter);
            retrievalTraceRepository.save(trace);
        } catch (Exception e) {
            log.warn("记录检索Trace失败 - err: {}", e.getMessage());
        }
    }

    /** 从登录上下文取当前用户名（可能为空）；principal 为 record 时 getName() 返回 toString()，须显式取 username。 */
    private String currentUserId() {
        return org.example.security.SecurityUtil.currentUsername();
    }

    /**
     * 对 RRF 融合结果做语义精排。
     *
     * <p>仅当重排模型可用时生效：取前 {@code topN} 个候选交由本地 reranker 按与 query 的相关度打分，
     * 分数降序取前 {@code topK}。任何异常（模型为 null、推理失败等）都降级返回 RRF 原结果，
     * 不阻断检索链路。</p>
     */
    private List<KbChunkHit> rerank(List<KbChunkHit> candidates, String query, int topN, int topK,
                                    boolean rerankEnabled) {
        if (rerankScoringModel == null || !rerankEnabled || candidates.isEmpty()) {
            return candidates;
        }
        int n = Math.min(topN, candidates.size());
        List<KbChunkHit> pool = candidates.subList(0, n);
        try {
            List<TextSegment> segments = pool.stream()
                    .map(h -> TextSegment.from(h.getText()))
                    .collect(Collectors.toList());
            List<Double> scores = rerankScoringModel.scoreAll(segments, query).content();
            // 防御：重排返回条数异常时收缩到实际条数，避免越界后整批降级丢失重排
            int actualN = Math.min(n, scores == null ? 0 : scores.size());
            if (actualN <= 0) {
                return candidates.size() > topK ? candidates.subList(0, topK) : candidates;
            }
            // 按重排分数降序，稳定映射回候选
            List<Integer> order = new ArrayList<>(actualN);
            for (int i = 0; i < actualN; i++) {
                order.add(i);
            }
            order.sort((i, j) -> Double.compare(
                    scores.get(j) == null ? 0 : scores.get(j),
                    scores.get(i) == null ? 0 : scores.get(i)));
            List<KbChunkHit> reranked = new ArrayList<>(Math.min(topK, actualN));
            for (int i = 0; i < Math.min(topK, actualN); i++) {
                // 用 reranker 相关度分数覆盖命中分，供相关度拒答（RelevanceChecker）按真实相关度判定
                KbChunkHit src = pool.get(order.get(i));
                Double rerankScore = scores.get(order.get(i));
                reranked.add(KbChunkHit.builder()
                        .docId(src.getDocId())
                        .chunkIndex(src.getChunkIndex())
                        .title(src.getTitle())
                        .filePath(src.getFilePath())
                        .text(src.getText())
                        .score(rerankScore != null ? rerankScore : 0.0)
                        .source(src.getSource())
                        .siteSlug(src.getSiteSlug())
                        .build());
            }
            return reranked;
        } catch (Exception e) {
            log.warn("语义重排失败，降级返回 RRF 融合结果 - err: {}", e.getMessage());
            return candidates.size() > topK ? candidates.subList(0, topK) : candidates;
        }
    }

    @Override
    public String retrieveForLlm(String query, int topK) {
        List<KbChunkHit> hits = search(query, topK);
        if (hits.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            KbChunkHit hit = hits.get(i);
            sb.append("【片段").append(i + 1).append("「").append(hit.getTitle()).append("」( ")
                    .append(hit.getFilePath()).append(")】\n");
            sb.append(hit.getText()).append("\n\n");
        }
        return sb.toString().trim();
    }

    /**
     * 关键词召回（多空间）：Redis BM25 路与空间无关（结果后续按 docId 集合过滤）只查一次；
     * MySQL LIKE 路按空间逐个查询后合并去重。
     */
    private List<KbChunkHit> keywordRetrieve(List<String> libraryIds, String query, int k) {
        List<String> tokens = tokenize(query);
        if (tokens.isEmpty()) {
            return new ArrayList<>();
        }
        // 优先 RediSearch BM25（中文分词索引，词级相关度远优于 LIKE 子查询）；
        // 无命中或异常时回退 MySQL LIKE，保证关键词一路始终可用
        if (redisKeywordSearchEnabled) {
            try {
                List<KbChunkHit> redisHits = redisBm25KeywordSearch.search(tokens, k);
                if (!redisHits.isEmpty()) {
                    return redisHits;
                }
                log.info("Redis BM25 无命中，回退 MySQL LIKE - query: {}", truncateForLog(query));
            } catch (Exception e) {
                log.warn("Redis BM25 检索失败，回退 MySQL LIKE - err: {}", e.getMessage());
            }
        }
        // 逐空间逐词条查询后合并去重（OR 语义：任一词命中即计入）；同一分块命中多个词条时累加得分，
        // 让多词高相关分块在 RRF 融合/溯源中保持更高置信度，且不再丢失来源路信息。
        java.util.LinkedHashMap<String, KbChunkHit> merged = new java.util.LinkedHashMap<>();
        for (String libraryId : libraryIds) {
            if (libraryId == null || libraryId.isBlank()) {
                continue;
            }
            for (String token : tokens) {
                List<KnowledgeChunkEntity> chunks;
                try {
                    chunks = chunkRepository.keywordSearch(libraryId, token, PageRequest.of(0, k));
                } catch (Exception e) {
                    log.warn("关键词检索失败，跳过词条 - token: {}, err: {}", token, e.getMessage());
                    continue;
                }
            for (KnowledgeChunkEntity chunk : chunks) {
                KnowledgeDocumentEntity doc = docById(chunk.getDocId());
                if (doc == null) {
                    continue;
                }
                String key = chunk.getDocId() + "#" + chunk.getChunkIndex();
                KbChunkHit hit = merged.get(key);
                if (hit == null) {
                    hit = KbChunkHit.builder()
                            .docId(chunk.getDocId())
                            .chunkIndex(chunk.getChunkIndex())
                            .title(doc.getTitle())
                            .filePath(doc.getFilePath())
                            .siteSlug(doc.getSiteSlug())
                            .text(chunk.getText())
                            .score(KEYWORD_MATCH_SCORE)
                            .source(RESULT_SOURCE_KEYWORD)
                            .build();
                    merged.put(key, hit);
                } else {
                    // 多词条命中同一分块：累加得分，凸显相关度
                    hit.setScore((hit.getScore() == null ? 0 : hit.getScore()) + KEYWORD_MATCH_SCORE);
                }
            }
            }
        }
        // 按得分降序、docId/chunkIndex 次序稳定截断到 topK
        return merged.values().stream()
                .sorted(Comparator.comparing((KbChunkHit h) -> h.getScore() == null ? 0d : h.getScore()).reversed()
                        .thenComparing(KbChunkHit::getDocId)
                        .thenComparing(KbChunkHit::getChunkIndex))
                .limit(k)
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 轻量分词：按空白与常用中英文标点切分，保留技术术语（API 名、错误码、类名）。
     * 停用标点被切掉，即配置中"去除停用标点、保留词条"的语义。
     * 为避免过多词条导致多余的数据库查询与噪声，限制词条数上限、丢弃单字符噪声词条。
     */
    private static final int MAX_KEYWORD_TOKENS = 4;

    private List<String> tokenize(String query) {
        String text = query == null ? "" : query.trim();
        if (text.isEmpty()) {
            return new ArrayList<>();
        }
        String[] parts = text.split("[\\s,，。;；、:：\\-—_/|()（）]+");
        java.util.LinkedHashSet<String> tokens = new java.util.LinkedHashSet<>();
        for (String p : parts) {
            String t = sanitizeKeyword(p);
            // 丢弃单字符噪声词条（如单个字/符号），避免似 LIKE、护词条数上限
            if (t.length() >= 2) {
                tokens.add(t);
                if (tokens.size() >= MAX_KEYWORD_TOKENS) {
                    break;
                }
            }
        }
        // 若切分后均为空或过短（如纯标点/单字查询），退回整体去通配符后的单一条目，避免丢失召回能力
        if (tokens.isEmpty()) {
            String whole = sanitizeKeyword(text);
            if (!whole.isEmpty()) {
                tokens.add(whole);
            }
        }
        return new ArrayList<>(tokens);
    }

    /** 清除 LIKE 通配符元字符（% _），避免关键词被当作 SQL 通配符造成错误匹配。 */
    private String sanitizeKeyword(String token) {
        return token.replaceAll("[%_]", "");
    }

    /** 日志用查询截断，避免超长查询刷爆日志。 */
    private String truncateForLog(String query) {
        String line = query == null ? "" : query.replaceAll("\\s+", " ").trim();
        return line.length() <= 30 ? line : line.substring(0, 30) + "…";
    }

    private KnowledgeDocumentEntity docById(Long id) {
        KnowledgeDocumentEntity doc = docCache.get(id);
        if (doc == null) {
            doc = documentRepository.findById(id).orElse(null);
            if (doc != null) {
                docCache.put(id, doc);
            }
        }
        return doc;
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String toStringVal(Object value) {
        return value == null ? "" : value.toString();
    }

    private String defaultEmpty(String value) {
        return value == null ? "" : value;
    }
}
