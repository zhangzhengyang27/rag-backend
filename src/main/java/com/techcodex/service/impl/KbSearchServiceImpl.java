package com.techcodex.service.impl;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import com.techcodex.config.KbConfig;
import com.techcodex.dto.KbChunkHit;
import com.techcodex.dto.SearchOptions;
import com.techcodex.entity.KnowledgeChunkEntity;
import com.techcodex.entity.KnowledgeSectionEntity;
import com.techcodex.entity.RetrievalTraceEntity;
import com.techcodex.entity.KnowledgeDocumentEntity;
import com.techcodex.repository.KnowledgeChunkRepository;
import com.techcodex.repository.KnowledgeDocumentRepository;
import com.techcodex.repository.KnowledgeSectionRepository;
import com.techcodex.repository.RetrievalTraceRepository;
import com.techcodex.service.KbSearchService;
import com.techcodex.service.RrfFuser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

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
    private final com.techcodex.config.RuntimeConfigStore runtimeConfig;
    /** 文档信息本地缓存（有界 LRU，文档更新/删除时由服务层失效），减少混合检索时的重复查询。 */
    private final com.techcodex.service.DocumentInfoCache docCache;
    /** RediSearch BM25 关键词检索（中文分词索引），keyword-backend=redis 时启用 */
    private final com.techcodex.search.RedisBm25KeywordSearch redisBm25KeywordSearch;
    /** 章节父块：可选注入，缺失时检索仍可用（只是不扩展父级上下文）。 */
    @Autowired(required = false)
    private KnowledgeSectionRepository sectionRepository;
    /** 批量子块补全：把逐命中 2 次 DB 查询压成 2 次批查询；单测/非 Spring 场景可为 null，自动走原逐条路径。 */
    @Autowired(required = false)
    private JdbcTemplate batchJdbcTemplate;
    private final boolean redisKeywordSearchEnabled;

    /**
     * 语义重排模型（本地 bge-reranker INT8 ONNX）。可为 null：
     * 未启用或模型加载失败时不注入，检索降级为纯 RRF 融合结果。
     * 用 setter 注入而不是字段注入：字段注入在代理/多实例场景下曾出现未注入，
     * setter 能保证启动期显式完成一次赋值，并保留 required=false 的可选语义。
     */
    private ScoringModel rerankScoringModel;

    @Autowired(required = false)
    public void setRerankScoringModel(ScoringModel rerankScoringModel) {
        this.rerankScoringModel = rerankScoringModel;
    }

    /** 关键词路每个词条命中的基础置信度分（多个词条命中同一分块时累加） */
    private static final double KEYWORD_MATCH_SCORE = 1.0;
    /** 标题命中加权：让“标题完全匹配问题”的文档优先进入 RRF，避免被通用正文条目压住 */
    private static final double TITLE_MATCH_BOOST = 0.6;
    /** 召回来源路标识：向量召回 */
    private static final String RESULT_SOURCE_VECTOR = "vector";
    /** 召回来源路标识：关键词召回 */
    private static final String RESULT_SOURCE_KEYWORD = "keyword";
    /** 标题词法通道：单条查询最多分析 8 个词，避免长问句被通用词淹没。 */
    private static final int TITLE_TOKEN_LIMIT = 8;
    /** 标题词法通道：每个稀有词最多取 5 篇标题候选，按词分别保底，防止一个高频词占满候选。 */
    private static final int TITLE_PER_TOKEN_LIMIT = 5;
    /** 标题词法通道最多回填 12 篇文档，融合后仍只保留 finalTopK 条给 LLM。 */
    private static final int TITLE_MAX_DOCS = 12;
    /** 标题索引缓存 TTL：标题变更由同步任务触发全量重建，5 分钟内允许读到旧标题，换来检索侧零全表扫描。 */
    private static final long TITLE_CACHE_TTL_MS = 5 * 60 * 1000L;
    private final Map<String, TitleCache> titleCache = new ConcurrentHashMap<>();
    private static final Pattern TITLE_PUNCT = Pattern.compile("[？?！!，,。;；、：:（）()\\[\\]{}\"“”]+");
    private static final Pattern TITLE_CONNECTOR = Pattern.compile("如果|遇到|以及|和|与");
    private static final Set<String> TITLE_STOP_WORDS = Set.of(
            "的", "了", "在", "里", "中", "对", "从", "到", "按", "把", "被", "会", "可以", "进行",
            "如何", "怎么", "什么", "为什么", "哪些", "哪个", "是什么", "有什么", "区别", "关系",
            "应该", "步骤", "代表", "影响", "完成", "触发", "配合", "协作", "处理", "支撑",
            "说明", "介绍", "解释", "一下", "吗", "呢", "更", "快");
    /** 同主题兄弟扩展最多补 2 篇：多跳通常两篇目标，留一篇余量；只在多概念问句（topK≥6）时启用。 */
    private static final int SIBLING_MAX_DOCS = 2;
    /** 兄弟扩展的最小二元组得分阈值，避免仅凭“事务/缓存”这类泛词就引入无关文档。 */
    private static final double SIBLING_MIN_SCORE = 1.0;
    /** 兄弟标题匹配时丢弃的问句二元组；它们只表达提问方式，不是主题词。 */
    private static final Set<String> SIBLING_STOP_BIGRAMS = Set.of(
            "什么", "如何", "怎么", "哪些", "哪个", "如果", "应该", "可以", "一个", "我们",
            "就是", "不是", "还是", "以及", "或者", "因为", "所以", "但是", "然后", "这个",
            "那个", "进行", "需要", "支持", "使用", "对于", "通过", "之间", "发生");

    public KbSearchServiceImpl(EmbeddingModel embeddingModel,
                               EmbeddingStore<TextSegment> kbEmbeddingStore,
                               KnowledgeChunkRepository chunkRepository,
                               KnowledgeDocumentRepository documentRepository,
                               RetrievalTraceRepository retrievalTraceRepository,
                               KbConfig kbConfig,
                               com.techcodex.config.RuntimeConfigStore runtimeConfig,
                               com.techcodex.service.DocumentInfoCache docCache,
                               com.techcodex.search.RedisBm25KeywordSearch redisBm25KeywordSearch,
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
        return searchAs(com.techcodex.security.SecurityUtil.currentLibraryId(kbConfig.getPublicLibrary()),
                com.techcodex.security.SecurityUtil.currentUsername(), query, topK, pathFilter, space, options);
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
        long stageNanos = startNanos;
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
        int configuredFinalTopK = runtimeConfig.getInt("retrieval.final-top-k");
        int finalTopK = options != null && options.getFinalTopK() != null
                ? Math.min(k, Math.max(1, options.getFinalTopK()))
                : Math.min(k, configuredFinalTopK);
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
        long embedDone = System.nanoTime();
        EmbeddingSearchResult<TextSegment> result = kbEmbeddingStore.search(request);
        long vectorDone = System.nanoTime();

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
            String anchorMeta = toStringVal(meta.get("anchor"));
            if (anchorMeta == null || anchorMeta.isBlank()) {
                anchorMeta = toStringVal(meta.get("heading"));
            }
            vectorHits.add(KbChunkHit.builder()
                    .docId(id)
                    .chunkIndex(chunkIndex)
                    .title(defaultEmpty(toStringVal(meta.get("title"))))
                    .filePath(defaultEmpty(toStringVal(meta.get("filePath"))))
                    .siteSlug(defaultEmpty(toStringVal(meta.get("siteSlug"))))
                    .headingPath(defaultEmpty(toStringVal(meta.get("headingPath"))))
                    .heading(defaultEmpty(toStringVal(meta.get("heading"))))
                    .anchor(defaultEmpty(anchorMeta))
                    .headingOrdinal(toInteger(meta.get("headingOrdinal")))
                    .chunkType(defaultEmpty(toStringVal(meta.get("chunkType"))))
                    .codeLang(defaultEmpty(toStringVal(meta.get("codeLang"))))
                    .tableHeader(defaultEmpty(toStringVal(meta.get("tableHeader"))))
                    .text(segment.text())
                    .score(match.score())
                    .source(RESULT_SOURCE_VECTOR)
                    .build());
        }
        // 旧向量索引未声明新 metadata 字段时，按 docId+chunkIndex 回查 kb_chunk 补齐；
        // 新索引已带字段时这里不会产生额外查询。
        enrichFromDb(vectorHits);

        // 2. 关键词召回（MySQL 全文/模糊，对中文技术文档术语友好）
        List<KbChunkHit> keywordHits = keywordRetrieve(libraryIds, query, bm25TopN);
        long keywordDone = System.nanoTime();
        // Redis BM25 路不带 heading/code 字段，统一回查；MySQL 路已有字段时不会多查
        enrichFromDb(keywordHits);
        if (filterDocIds != null) {
            keywordHits.removeIf(h -> !filterDocIds.contains(h.getDocId()));
        }

        // 2.1 文档标题命中加权：技术问题经常直接以文档标题提问（如“HashMap 的扩容机制”），
        // 但分块向量会被正文里的其他高频词带偏；把标题命中的文档排到两路召回前面，
        // 再由 RRF 和 reranker 做最终裁决，避免“标题完全匹配却排在通用文章后面”。
        List<String> queryTokens = tokenize(query);
        applyTitleBoost(vectorHits, queryTokens);
        applyTitleBoost(keywordHits, queryTokens);

        long enrichDone = System.nanoTime();
        // 2.2 标题词法通道：从文档标题中召回查询里的稀有技术词（如 Vite、Promise、MVCC）。
        // 向量/BM25 会漏掉“正文语义不近、但标题就是目标实体”的文档；标题通道只补候选，
        // 最终仍由 RRF 融合排序，不直接覆盖向量/关键词结果。
        List<KbChunkHit> titleLexicalHits = runtimeConfig.getBool("retrieval.title-lexical-enabled")
                ? titleLexicalHits(libraryIds, query, filterDocIds, finalTopK)
                : java.util.Collections.emptyList();

        // 2.3 精确标题召回：问题里完整引用《文档标题》时，把该文档首块直接放入候选。
        // 这不是“关键词加权”，而是显式实体链接：用户已经点名文章，检索不应再靠向量猜测。
        // 只有显式书名号引用才走精确标题扫描：JPQL 的 locate/replace 需要全表扫描，
        // 普通问句跑这一路会稳定贡献 100ms+ 尾延迟；标题加权已覆盖“直接以标题提问”的场景。
        boolean explicitTitleRef = query != null && query.indexOf('《') >= 0 && query.indexOf('》') > query.indexOf('《');
        List<KbChunkHit> exactTitleHits = explicitTitleRef
                ? exactTitleHits(libraryIds, query, filterDocIds, finalTopK)
                : java.util.Collections.emptyList();

        // 3. RRF 加权融合（向量 + 关键词 + 标题词法三路），而非简单补位
        List<List<KbChunkHit>> fusionRuns = new ArrayList<>();
        List<Double> fusionWeights = new ArrayList<>();
        fusionRuns.add(vectorHits);
        fusionWeights.add(1.0);
        if (bm25Enabled && !keywordHits.isEmpty()) {
            fusionRuns.add(keywordHits);
            fusionWeights.add(1.0);
        }
        if (!titleLexicalHits.isEmpty()) {
            fusionRuns.add(titleLexicalHits);
            // 标题通道候选少但精度高，默认与其他路等价；调高需用标准评测验证不伤 HitRate。
            fusionWeights.add(runtimeConfig.getDouble("retrieval.title-lexical-weight"));
        }
        List<KbChunkHit> merged;
        if (fusionRuns.size() > 1) {
            merged = RrfFuser.fuseWeighted(fusionRuns, fusionWeights,
                    runtimeConfig.getInt("retrieval.rrf-k"), finalTopK);
        } else {
            // 关键词/标题通道都关闭时退回纯向量召回，截断到 finalTopK
            merged = vectorHits.size() > finalTopK ? vectorHits.subList(0, finalTopK) : vectorHits;
        }
        // 3.1 标题概念覆盖：每个稀有查询词至少留一篇“标题含该词”的文档。
        // 多跳问题里两个概念各自可能被通用语义候选挤掉；这一步只补“标题锚点”，
        // 不改变原有候选顺序，也不引入开放域 NER。
        merged = applyTitleConceptCoverage(merged, titleLexicalHits, query, finalTopK);
        // 3.2 同主题兄弟文档：多跳问题的第二篇常常和首篇在同一目录（如“执行计划”与“索引排障”），
        // 但标题没有直接词面交集，向量/BM25/标题通道都补不进来。这里只对多概念问句（topK≥6）启用，
        // 用“同目录 + 稀有标题二元组重叠”做一跳扩展；仅多概念问句触发，避免污染单跳排序。
        merged = applySiblingCoverage(merged, query, libraryIds, finalTopK);

        if (!exactTitleHits.isEmpty()) {
            java.util.LinkedHashMap<String, KbChunkHit> withExactTitle = new java.util.LinkedHashMap<>();
            for (KbChunkHit hit : exactTitleHits) {
                withExactTitle.putIfAbsent(hit.getDocId() + "#" + hit.getChunkIndex(), hit);
            }
            for (KbChunkHit hit : merged) {
                withExactTitle.putIfAbsent(hit.getDocId() + "#" + hit.getChunkIndex(), hit);
            }
            merged = new ArrayList<>(withExactTitle.values());
            if (merged.size() > finalTopK) {
                merged = new ArrayList<>(merged.subList(0, finalTopK));
            }
        }

        // 4. 语义重排精排（可选）：用本地 bge-reranker 对 RRF 融合后的 topN 再排序
        boolean rerankModelAvailable = rerankScoringModel != null && rerankEnabled;
        merged = rerank(merged, query, runtimeConfig.getInt("retrieval.rerank-top-n"), finalTopK, rerankEnabled);

        long totalDone = System.nanoTime();
        log.info("检索阶段耗时 - embed: {}ms, vector: {}ms, keyword+enrich: {}ms, title+merge+rerank: {}ms, total: {}ms, q: {}",
                (embedDone - stageNanos) / 1_000_000, (vectorDone - embedDone) / 1_000_000,
                (enrichDone - vectorDone) / 1_000_000, (totalDone - enrichDone) / 1_000_000,
                (totalDone - startNanos) / 1_000_000, truncateForLog(query));
        // 离线评测不写 trace：合成查询（尤其分解后的子查询）会把 P95 样本拖向评测流量，
        // 让线上容量判断失真；生产请求默认仍记录。
        boolean recordTraceEnabled = options == null || options.getRecordTrace() == null || options.getRecordTrace();
        if (recordTraceEnabled) {
            recordTrace(truncateLibrary(joinLibrarys(libraryIds)), query, pathFilter, vectorHits.size(), keywordHits.size(), merged.size(),
                    rerankModelAvailable, rerankModelAvailable, startNanos);
        }
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
        final String libraryId = com.techcodex.security.SecurityUtil.currentLibraryId(kbConfig.getPublicLibrary());
        final String username = com.techcodex.security.SecurityUtil.currentUsername();
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
        return com.techcodex.security.SecurityUtil.currentUsername();
    }

    /**
     * 对 RRF 融合结果做语义精排。
     *
     * <p>仅当重排模型可用时生效：取前 {@code topN} 个候选交由本地 reranker 按与 query 的相关度打分，
     * 分数降序取前 {@code topK}。任何异常（模型为 null、推理失败等）都降级返回 RRF 原结果，
     * 不阻断检索链路。</p>
     */
    private static final int RERANK_MAX_CHARS = 500;

    private List<KbChunkHit> rerank(List<KbChunkHit> candidates, String query, int topN, int topK,
                                    boolean rerankEnabled) {
        if (rerankScoringModel == null || !rerankEnabled || candidates.isEmpty()) {
            return candidates;
        }
        int n = Math.min(topN, candidates.size());
        List<KbChunkHit> pool = candidates.subList(0, n);
        try {
            // reranker 只截取正文前 500 字：长分块超出模型 510 token 后本来就会被截断，
            // 先截断可避免 tokenizer 处理整段长文带来的尾延迟尖刺。
            List<TextSegment> segments = pool.stream()
                    .map(h -> TextSegment.from(truncateForRerank(h.getText())))
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
            java.util.Set<Integer> rerankedCandidateIndexes = new java.util.HashSet<>();
            for (int i = 0; i < Math.min(topK, actualN); i++) {
                // 用 reranker 相关度分数覆盖命中分，供相关度拒答（RelevanceChecker）按真实相关度判定
                KbChunkHit src = pool.get(order.get(i));
                rerankedCandidateIndexes.add(order.get(i));
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
                        .headingPath(src.getHeadingPath())
                        .heading(src.getHeading())
                        .anchor(src.getAnchor())
                        .headingOrdinal(src.getHeadingOrdinal())
                        .chunkType(src.getChunkType())
                        .codeLang(src.getCodeLang())
                        .tableHeader(src.getTableHeader())
                        // rerank 会重建命中对象，父子上下文/表头等新字段必须显式透传，
                        // 否则召回时有、重排后又被丢掉（曾导致 parentContext 一直为 null）。
                        .parentId(src.getParentId())
                        .parentHeadingPath(src.getParentHeadingPath())
                        .parentContext(src.getParentContext())
                        .build());
            }
            // topK 大于重排窗口时，把未进入重排的候选按 RRF 原序补回，避免“请求 10 条只返回 6 条”。
            // 重排只负责前 N 条的精度，后续补位仍保留原融合顺序。
            if (reranked.size() < topK && candidates.size() > reranked.size()) {
                for (int i = 0; i < candidates.size() && reranked.size() < topK; i++) {
                    if (i < n && rerankedCandidateIndexes.contains(i)) {
                        continue;
                    }
                    reranked.add(candidates.get(i));
                }
            }
            return reranked;
        } catch (Exception e) {
            log.warn("语义重排失败，降级返回 RRF 融合结果 - err: {}", e.getMessage());
            return candidates.size() > topK ? candidates.subList(0, topK) : candidates;
        }
    }

    /** 父级上下文最多 2,000 字：足够补充定义/前后段，又不会把 5 条引用撑爆 prompt。 */
    private String truncateSection(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 2_000 ? text : text.substring(0, 2_000);
    }

    private String truncateForRerank(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= RERANK_MAX_CHARS ? text : text.substring(0, RERANK_MAX_CHARS);
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
     * 检索命中补齐结构元数据：向量与 Redis BM25 两路都可能来自旧索引（没有
     * headingPath/heading/chunkType 字段），按 docId + chunkIndex 回查 kb_chunk。
     * 已经有 chunkType 的命中（MySQL 关键词路或新索引）直接跳过，避免无谓查询。
     */
    /** 从 headingPath 取最后一段作为当前标题文本（实体没有单独 heading 列时的兜底）。 */
    private static String headingFromPath(String headingPath) {
        if (headingPath == null || headingPath.isBlank()) {
            return "";
        }
        int idx = headingPath.lastIndexOf(" > ");
        return idx >= 0 ? headingPath.substring(idx + 3) : headingPath;
    }

    private void enrichFromDb(List<KbChunkHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return;
        }
        List<KbChunkHit> need = new ArrayList<>();
        for (KbChunkHit hit : hits) {
            if (hit.getDocId() == null || hit.getChunkIndex() == null) {
                continue;
            }
            // 旧向量索引里的 anchor 是标题原文（可能含空格），新索引是 slug；
            // 只要发现旧格式就回查数据库覆盖，避免引用跳转继续使用过期锚点。
            // 新格式/空值不需要额外查询，保持检索延迟稳定。
            boolean needsAnchorRefresh = hit.getAnchor() == null
                    || hit.getAnchor().isBlank()
                    || hit.getAnchor().chars().anyMatch(Character::isWhitespace);
            // parentContext 是父子分块的新字段：旧命中也必须回查，不能用 chunkType 提前跳过。
            if (hit.getChunkType() != null && !hit.getChunkType().isBlank()
                    && !needsAnchorRefresh && hit.getParentContext() != null) {
                continue;
            }
            need.add(hit);
        }
        if (need.isEmpty()) {
            return;
        }
        if (batchJdbcTemplate != null && enrichBatch(need)) {
            return;
        }
        // 兜底：非 Spring 单测或批查询异常时，保留逐条回查。
        for (KbChunkHit hit : need) {
            chunkRepository.findByDocIdAndChunkIndex(hit.getDocId(), hit.getChunkIndex())
                    .ifPresent(chunk -> applyChunkMeta(hit, chunk));
        }
    }

    /** 批量补全子块元数据 + 父级 section；成功返回 true，异常返回 false 由调用方降级。 */
    private boolean enrichBatch(List<KbChunkHit> hits) {
        try {
            // 行构造器 IN 让优化器按 (doc_id, chunk_index) 组合索引定位；
            // 20 个 OR 等值条件在部分统计信息下会退化成全表扫描，是尾延迟尖峰的来源。
            StringBuilder sql = new StringBuilder(
                    "SELECT doc_id, chunk_index, heading_path, anchor, heading_ordinal, chunk_type, code_lang, table_header, parent_id " +
                            "FROM kb_chunk WHERE (doc_id, chunk_index) IN (");
            List<Object> args = new ArrayList<>(hits.size() * 2);
            for (int i = 0; i < hits.size(); i++) {
                if (i > 0) {
                    sql.append(',');
                }
                sql.append("(?, ?)");
                args.add(hits.get(i).getDocId());
                args.add(hits.get(i).getChunkIndex());
            }
            sql.append(')');
            List<Map<String, Object>> rows = batchJdbcTemplate.queryForList(sql.toString(), args.toArray());
            Map<String, Map<String, Object>> byKey = new java.util.HashMap<>();
            List<Object[]> sectionPairs = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Object docId = row.get("doc_id");
                Object chunkIndex = row.get("chunk_index");
                if (docId == null || chunkIndex == null) {
                    continue;
                }
                byKey.put(docId + "#" + chunkIndex, row);
                if (row.get("heading_ordinal") != null) {
                    sectionPairs.add(new Object[]{docId, row.get("heading_ordinal")});
                }
            }
            Map<String, Map<String, Object>> sectionsByKey = new java.util.HashMap<>();
            if (!sectionPairs.isEmpty()) {
                StringBuilder sectionSql = new StringBuilder(
                        "SELECT id, doc_id, heading_ordinal, heading_path, text FROM kb_section WHERE (doc_id, heading_ordinal) IN (");
                List<Object> sectionArgs = new ArrayList<>(sectionPairs.size() * 2);
                for (int i = 0; i < sectionPairs.size(); i++) {
                    if (i > 0) {
                        sectionSql.append(',');
                    }
                    sectionSql.append("(?, ?)");
                    sectionArgs.add(sectionPairs.get(i)[0]);
                    sectionArgs.add(sectionPairs.get(i)[1]);
                }
                sectionSql.append(')');
                for (Map<String, Object> row : batchJdbcTemplate.queryForList(sectionSql.toString(), sectionArgs.toArray())) {
                    sectionsByKey.put(row.get("doc_id") + "#" + row.get("heading_ordinal"), row);
                }
            }
            for (KbChunkHit hit : hits) {
                Map<String, Object> row = byKey.get(hit.getDocId() + "#" + hit.getChunkIndex());
                if (row == null) {
                    continue;
                }
                hit.setHeadingPath((String) row.get("heading_path"));
                String headingPath = (String) row.get("heading_path");
                hit.setHeading(headingFromPath(headingPath));
                hit.setAnchor((String) row.get("anchor"));
                hit.setHeadingOrdinal((Integer) row.get("heading_ordinal"));
                hit.setChunkType((String) row.get("chunk_type"));
                hit.setCodeLang((String) row.get("code_lang"));
                hit.setTableHeader((String) row.get("table_header"));
                Object parentId = row.get("parent_id");
                hit.setParentId(parentId == null ? null : ((Number) parentId).longValue());
                Map<String, Object> section = sectionsByKey.get(hit.getDocId() + "#" + hit.getHeadingOrdinal());
                if (section != null) {
                    Object sectionId = section.get("id");
                    hit.setParentId(sectionId == null ? null : ((Number) sectionId).longValue());
                    hit.setParentHeadingPath((String) section.get("heading_path"));
                    hit.setParentContext(truncateSection((String) section.get("text")));
                }
            }
            return true;
        } catch (Exception e) {
            log.warn("批量补全子块失败，降级逐条回查 - err: {}", e.getMessage());
            return false;
        }
    }

    private void applyChunkMeta(KbChunkHit hit, KnowledgeChunkEntity chunk) {
        hit.setHeadingPath(chunk.getHeadingPath());
        // 实体只有 anchor 列：新索引存 slug，旧索引存标题文本；
        // heading 一律从 headingPath 最后一段派生，避免把 slug 当标题展示。
        hit.setHeading(headingFromPath(chunk.getHeadingPath()));
        hit.setAnchor(chunk.getAnchor());
        hit.setHeadingOrdinal(chunk.getHeadingOrdinal());
        hit.setChunkType(chunk.getChunkType());
        hit.setCodeLang(chunk.getCodeLang());
        hit.setTableHeader(chunk.getTableHeader());
        hit.setParentId(chunk.getParentId());
        // 父级按 docId + ordinal 取，parent_id 只作关联记录；旧数据未回填时也能命中。
        if (sectionRepository != null && chunk.getHeadingOrdinal() != null) {
            KnowledgeSectionEntity section = sectionRepository
                    .findByDocIdAndHeadingOrdinal(chunk.getDocId(), chunk.getHeadingOrdinal())
                    .orElse(null);
            if (section != null) {
                hit.setParentId(section.getId());
                hit.setParentHeadingPath(section.getHeadingPath());
                hit.setParentContext(truncateSection(section.getText()));
            }
        }
    }

    /**
     * 关键词召回（多空间）：Redis BM25 路与空间无关（结果后续按 docId 集合过滤）只查一次；
     * MySQL LIKE 路按空间逐个查询后合并去重。
     */
    /**
     * 标题词法召回：把查询拆成稀有技术词，按“每个词各自取前 5 篇标题命中”保底召回。
     * 这是轻量多跳的“实体通道”，不替代向量语义召回：它只负责把标题即实体的文档送进 RRF，
     * 避免“Vite / Promise / MVCC”这类词在正文语义不近时被通用词挤掉。
     */
    private List<KbChunkHit> titleLexicalHits(List<String> libraryIds, String query,
                                              Set<Long> filterDocIds, int limit) {
        if (query == null || query.isBlank() || libraryIds == null || libraryIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<TitleDoc> docs = titleIndex(libraryIds);
        if (docs.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> tokens = titleTokens(query);
        if (tokens.isEmpty()) {
            return Collections.emptyList();
        }
        // 只保留稀有词：df 超过文档数 2.5%（且不超过 60）的词多为“Spring/MySQL/缓存”这类通用词，
        // 它们会把候选引到同主题的其他文档；稀有词才是能定位到具体实体文档的锚点。
        int dfLimit = Math.min(60, Math.max(3, docs.size() / 40));
        Map<Long, Double> scoreById = new HashMap<>();
        Map<Long, TitleDoc> docById = new HashMap<>();
        for (String token : tokens) {
            String needle = token.toLowerCase(Locale.ROOT);
            int df = 0;
            for (TitleDoc doc : docs) {
                if (doc.title().toLowerCase(Locale.ROOT).contains(needle)) {
                    df++;
                }
            }
            if (df == 0 || df > dfLimit) {
                continue;
            }
            double idf = Math.log((docs.size() + 1.0) / (df + 1.0)) + 1.0;
            List<TitleMatch> matches = new ArrayList<>();
            for (TitleDoc doc : docs) {
                String title = doc.title().toLowerCase(Locale.ROOT);
                if (title.contains(needle)) {
                    matches.add(new TitleMatch(doc, 0));
                } else if (needle.length() >= 3) {
                    // 后缀退一步：处理“执行计划里/约束冲突时”这类带方位/时态尾巴的词，
                    // 只用完整词会漏掉标题里只写“执行计划/约束冲突”的文档。
                    String suffix = needle.substring(needle.length() - 2);
                    if (title.contains(suffix)) {
                        matches.add(new TitleMatch(doc, 1));
                    }
                }
            }
            matches.sort(Comparator.comparingInt(TitleMatch::kind)
                    .thenComparingInt(m -> m.doc().title().length())
                    .thenComparingLong(m -> m.doc().id()));
            int count = Math.min(TITLE_PER_TOKEN_LIMIT, matches.size());
            for (int i = 0; i < count; i++) {
                TitleMatch match = matches.get(i);
                double score = idf * (match.kind() == 0 ? 1.0 : 0.55);
                // 同一文档命中多个稀有词时取最高分而不是累加：累加会让“标题含多个常见词”的
                // 长标题文档压过“标题就是该实体”的短文档，反而偏离实体召回目标。
                scoreById.merge(match.doc().id(), score, Math::max);
                docById.put(match.doc().id(), match.doc());
            }
        }
        if (scoreById.isEmpty()) {
            log.debug("标题词法通道无候选 - tokens: {}", tokens);
            return Collections.emptyList();
        }
        log.debug("标题词法通道候选 - tokens: {}, docs: {}", tokens, scoreById.keySet());
        List<Map.Entry<Long, Double>> ranked = new ArrayList<>(scoreById.entrySet());
        ranked.sort((a, b) -> {
            int cmp = Double.compare(b.getValue(), a.getValue());
            return cmp != 0 ? cmp : Long.compare(a.getKey(), b.getKey());
        });
        int maxDocs = Math.min(TITLE_MAX_DOCS, Math.max(6, limit * 2));
        List<KbChunkHit> hits = new ArrayList<>();
        for (Map.Entry<Long, Double> entry : ranked) {
            if (hits.size() >= maxDocs) {
                break;
            }
            Long docId = entry.getKey();
            if (filterDocIds != null && !filterDocIds.contains(docId)) {
                continue;
            }
            TitleDoc doc = docById.get(docId);
            if (doc == null) {
                continue;
            }
            KnowledgeChunkEntity chunk = chunkRepository.findFirstByDocIdOrderByChunkIndexAsc(docId).orElse(null);
            if (chunk == null) {
                continue;
            }
            hits.add(KbChunkHit.builder()
                    .docId(docId)
                    .chunkIndex(chunk.getChunkIndex())
                    .title(doc.title())
                    .filePath(doc.filePath())
                    .siteSlug(doc.siteSlug())
                    .headingPath(chunk.getHeadingPath())
                    .heading(headingFromPath(chunk.getHeadingPath()))
                    .anchor(chunk.getAnchor())
                    .headingOrdinal(chunk.getHeadingOrdinal())
                    .chunkType(chunk.getChunkType())
                    .codeLang(chunk.getCodeLang())
                    .tableHeader(chunk.getTableHeader())
                    .text(chunk.getText())
                    .score(entry.getValue())
                    .source("title_lexical")
                    .build());
        }
        // 标题通道只给了首块定位，补父级上下文交给统一批量补全，避免与向量/关键词路字段不一致。
        enrichFromDb(hits);
        return hits;
    }

    /**
     * 标题索引按空间缓存；标题同步是低频动作，5 分钟内允许旧标题，换取每次检索不再扫 4k+ 行。
     * 缓存键拼接空间 ID：多空间检索时每个空间集合各自维护，不做跨空间合并，避免命中错误文档。
     */
    private List<TitleDoc> titleIndex(List<String> libraryIds) {
        if (batchJdbcTemplate == null) {
            return Collections.emptyList();
        }
        String cacheKey = String.join(",", libraryIds);
        long now = System.currentTimeMillis();
        TitleCache cached = titleCache.get(cacheKey);
        if (cached != null && now - cached.loadedAt() < TITLE_CACHE_TTL_MS) {
            return cached.docs();
        }
        try {
            String placeholders = libraryIds.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(","));
            List<Map<String, Object>> rows = batchJdbcTemplate.queryForList(
                    "SELECT id, title, file_path, site_slug FROM kb_document WHERE library_id IN (" + placeholders + ")",
                    libraryIds.toArray());
            List<TitleDoc> docs = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                Object id = row.get("id");
                Object title = row.get("title");
                if (id == null || title == null || String.valueOf(title).isBlank()) {
                    continue;
                }
                docs.add(new TitleDoc(((Number) id).longValue(),
                        String.valueOf(title),
                        row.get("file_path") == null ? null : String.valueOf(row.get("file_path")),
                        row.get("site_slug") == null ? null : String.valueOf(row.get("site_slug"))));
            }
            titleCache.put(cacheKey, new TitleCache(docs, now));
            return docs;
        } catch (Exception e) {
            log.warn("标题索引加载失败，跳过标题词法通道 - err: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 标题词法分词与正文 BM25 分词分开：正文分词限制 5 个词、按空格/逗号切分，
     * 这里要保留 async/await、Cache-Control 这类带符号技术词，并把问号、如果、和、与当分隔符。
     * 之前不分问号会把“代表什么？如果索引存在…”整段当一个词，导致标题通道完全找不到实体。
     */
    private List<String> titleTokens(String query) {
        String text = query.replace("的", " ");
        text = TITLE_PUNCT.matcher(text).replaceAll(" ");
        text = TITLE_CONNECTOR.matcher(text).replaceAll(" ");
        text = text.replaceAll("(是怎样的|是什么|有哪些|哪几种|有什么区别|的区别|是怎么实现的|怎么实现|如何实现|为什么|怎么样|怎么|如何|请问|请解释|解释一下|由哪些部分组成|一下|代表什么|会失效|启动更快)", " ");
        List<String> tokens = new ArrayList<>();
        // 先放领域别名：多跳问句里的“代理机制”“约束冲突”在标题里可能写作 AOP、约束，
        // 先占住词条上限前部，避免长问句壳把真正的技术词挤到 8 条之外。
        for (String alias : com.techcodex.service.KbConceptAliases.expand(text)) {
            if (alias.length() >= 2 && !tokens.contains(alias)) {
                tokens.add(alias);
            }
            if (tokens.size() >= TITLE_TOKEN_LIMIT) {
                break;
            }
        }
        for (String part : text.split("[\\s_|@]+")) {
            String token = part.replaceAll("[%_]", "").replaceAll("(里|时|会|吗|呢)$", "").trim();
            if (token.length() >= 2 && !TITLE_STOP_WORDS.contains(token.toLowerCase(Locale.ROOT)
                    ) && !tokens.contains(token)) {
                tokens.add(token);
            }
            if (tokens.size() >= TITLE_TOKEN_LIMIT) {
                break;
            }
        }
        return tokens;
    }

    private record TitleDoc(Long id, String title, String filePath, String siteSlug) {
    }

    private record TitleCache(List<TitleDoc> docs, long loadedAt) {
    }

    private record TitleMatch(TitleDoc doc, int kind) {
    }

    /**
     * 标题概念覆盖：对查询里的每个稀有词，若 TopK 里还没有任何文档标题包含它，
     * 就从标题词法候选中补一篇最匹配的文档（替换末尾最低位候选）。
     * 最多补 3 篇：多跳问题通常 2 个概念，留 1 篇余量；补太多会让标准单跳检索变噪。
     */
    private List<KbChunkHit> applyTitleConceptCoverage(List<KbChunkHit> merged,
                                                       List<KbChunkHit> titleHits,
                                                       String query, int finalTopK) {
        if (merged == null || merged.isEmpty() || titleHits == null || titleHits.isEmpty()) {
            return merged;
        }
        List<String> tokens = titleTokens(query);
        Set<Long> presentDocs = new HashSet<>();
        for (KbChunkHit hit : merged) {
            if (hit.getDocId() != null) {
                presentDocs.add(hit.getDocId());
            }
        }
        List<KbChunkHit> anchors = new ArrayList<>();
        Set<Long> anchorDocs = new HashSet<>();
        for (String token : tokens) {
            boolean covered = false;
            for (KbChunkHit hit : merged) {
                if (titleContains(hit.getTitle(), token)) {
                    covered = true;
                    break;
                }
            }
            if (covered) {
                continue;
            }
            for (KbChunkHit hit : titleHits) {
                if (hit.getDocId() != null && !presentDocs.contains(hit.getDocId())
                        && !anchorDocs.contains(hit.getDocId())
                        && titleContains(hit.getTitle(), token)) {
                    anchors.add(hit);
                    anchorDocs.add(hit.getDocId());
                    break;
                }
            }
            if (anchors.size() >= 3) {
                break;
            }
        }
        if (anchors.isEmpty()) {
            return merged;
        }
        List<KbChunkHit> result = new ArrayList<>(merged);
        for (KbChunkHit anchor : anchors) {
            if (result.size() >= finalTopK) {
                result.remove(result.size() - 1);
            }
            result.add(anchor);
        }
        return result;
    }

    /**
     * 同主题兄弟文档扩展：从当前命中文档所在目录里，按“查询与标题共享的稀有二元组”挑 1–2 篇。
     * 这是轻量多跳的一跳扩散：不建实体图、不做 NER，只利用知识库目录本身就是主题簇这一结构事实。
     * 只在 finalTopK≥6 且多概念问句时启用：单概念查询不会因为这一步多出候选。
     */
    private List<KbChunkHit> applySiblingCoverage(List<KbChunkHit> merged, String query,
                                                  List<String> libraryIds, int finalTopK) {
        if (finalTopK < 6 || merged == null || merged.isEmpty() || !isMultiIntentQuery(query)) {
            return merged;
        }
        List<TitleDoc> docs = titleIndex(libraryIds);
        if (docs.isEmpty()) {
            return merged;
        }
        Map<Long, TitleDoc> byId = new HashMap<>();
        Map<String, List<TitleDoc>> byDir = new HashMap<>();
        for (TitleDoc doc : docs) {
            byId.put(doc.id(), doc);
            byDir.computeIfAbsent(dirOf(doc.filePath()), k -> new ArrayList<>()).add(doc);
        }
        Set<Long> presentDocs = new HashSet<>();
        for (KbChunkHit hit : merged) {
            if (hit.getDocId() != null) {
                presentDocs.add(hit.getDocId());
            }
        }
        // 查询里的中文二元组按标题文档频率算 IDF；只保留非问句壳的二元组，避免“如何/什么”把候选拉偏。
        Map<String, Double> queryBigramIdf = new HashMap<>();
        for (String bg : cjkBigrams(query)) {
            if (SIBLING_STOP_BIGRAMS.contains(bg)) {
                continue;
            }
            int df = 0;
            for (TitleDoc doc : docs) {
                if (doc.title().contains(bg)) {
                    df++;
                }
            }
            if (df > 0) {
                queryBigramIdf.put(bg, Math.log((docs.size() + 1.0) / (df + 1.0)) + 1.0);
            }
        }
        if (queryBigramIdf.isEmpty()) {
            return merged;
        }
        Map<Long, Double> siblingScore = new HashMap<>();
        Map<Long, Long> siblingParent = new HashMap<>();
        for (KbChunkHit hit : merged) {
            TitleDoc parent = byId.get(hit.getDocId());
            if (parent == null || parent.filePath() == null) {
                continue;
            }
            for (TitleDoc candidate : byDir.getOrDefault(dirOf(parent.filePath()), Collections.emptyList())) {
                if (presentDocs.contains(candidate.id())) {
                    continue;
                }
                double score = titleBigramScore(queryBigramIdf, candidate.title());
                if (score <= 0) {
                    continue;
                }
                // 同目录且文件名序号相邻，是“同主题上下篇”的强信号；只加 0.7，不压过标题词重叠。
                if (Math.abs(fileNumber(parent.filePath()) - fileNumber(candidate.filePath())) <= 1) {
                    score += 0.7;
                }
                if (score < SIBLING_MIN_SCORE) {
                    continue;
                }
                if (score > siblingScore.getOrDefault(candidate.id(), 0d)) {
                    siblingScore.put(candidate.id(), score);
                    siblingParent.put(candidate.id(), parent.id());
                }
            }
        }
        if (siblingScore.isEmpty()) {
            return merged;
        }
        List<Map.Entry<Long, Double>> ranked = new ArrayList<>(siblingScore.entrySet());
        ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        // 每个父文档最多带一篇兄弟，避免同一目录一次塞进多篇候选把 TopK 占满。
        Map<Long, Long> siblingForParent = new LinkedHashMap<>();
        for (Map.Entry<Long, Double> entry : ranked) {
            if (siblingForParent.size() >= SIBLING_MAX_DOCS) {
                break;
            }
            Long parentId = siblingParent.get(entry.getKey());
            if (parentId != null && !siblingForParent.containsKey(parentId)) {
                siblingForParent.put(parentId, entry.getKey());
            }
        }
        List<KbChunkHit> result = new ArrayList<>(merged);
        int added = 0;
        for (Map.Entry<Long, Long> entry : siblingForParent.entrySet()) {
            if (added >= SIBLING_MAX_DOCS) {
                break;
            }
            TitleDoc sibling = byId.get(entry.getValue());
            if (sibling == null) {
                continue;
            }
            KbChunkHit siblingHit = firstChunkHit(sibling, siblingScore.get(entry.getValue()), "sibling_doc");
            if (siblingHit == null) {
                continue;
            }
            // 替换末位而不是插到父文档后面：兄弟只是“可能有用的第二篇”，不能把原本正确的
            // 第二名候选挤下去；放末位让文档级 RRF 决定它是否进入最终 TopK。
            if (result.size() >= finalTopK) {
                result.remove(result.size() - 1);
            }
            result.add(siblingHit);
            added++;
        }
        return result;
    }

    /** 取标题候选的首块并补父级上下文，保证兄弟扩展与标题通道返回的字段口径一致。 */
    private KbChunkHit firstChunkHit(TitleDoc doc, double score, String source) {
        if (doc == null || doc.id() == null) {
            return null;
        }
        KnowledgeChunkEntity chunk = chunkRepository.findFirstByDocIdOrderByChunkIndexAsc(doc.id()).orElse(null);
        if (chunk == null) {
            return null;
        }
        KbChunkHit hit = KbChunkHit.builder()
                .docId(doc.id())
                .chunkIndex(chunk.getChunkIndex())
                .title(doc.title())
                .filePath(doc.filePath())
                .siteSlug(doc.siteSlug())
                .headingPath(chunk.getHeadingPath())
                .heading(headingFromPath(chunk.getHeadingPath()))
                .anchor(chunk.getAnchor())
                .headingOrdinal(chunk.getHeadingOrdinal())
                .chunkType(chunk.getChunkType())
                .codeLang(chunk.getCodeLang())
                .tableHeader(chunk.getTableHeader())
                .text(chunk.getText())
                .score(score)
                .source(source)
                .build();
        enrichFromDb(Collections.singletonList(hit));
        return hit;
    }

    /** 标题与查询共享的二元组 IDF 之和；标题完全没有主题词重叠时返回 0，不参与兄弟扩展。 */
    private double titleBigramScore(Map<String, Double> queryBigramIdf, String title) {
        if (title == null || title.isBlank()) {
            return 0;
        }
        Set<String> titleBigrams = cjkBigrams(title);
        double score = 0;
        for (Map.Entry<String, Double> entry : queryBigramIdf.entrySet()) {
            if (titleBigrams.contains(entry.getKey())) {
                score += entry.getValue();
            }
        }
        return score;
    }

    /** 中文连续片段拆成相邻二元组；英文/数字/符号不参与，避免把 MySQL、type=ALL 混进中文匹配。 */
    private Set<String> cjkBigrams(String text) {
        Set<String> bigrams = new HashSet<>();
        if (text == null || text.isBlank()) {
            return bigrams;
        }
        StringBuilder run = new StringBuilder();
        for (int i = 0; i <= text.length(); i++) {
            char c = i < text.length() ? text.charAt(i) : ' ';
            if (c >= 0x4E00 && c <= 0x9FFF) {
                run.append(c);
                continue;
            }
            if (run.length() >= 2) {
                for (int j = 0; j + 1 < run.length(); j++) {
                    bigrams.add(run.substring(j, j + 2));
                }
            }
            run.setLength(0);
        }
        return bigrams;
    }

    /** 文档所在目录；知识库的 file_path 是相对路径，目录即主题簇。 */
    private String dirOf(String filePath) {
        if (filePath == null) {
            return "";
        }
        String normalized = filePath.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? "" : normalized.substring(0, slash);
    }

    /** 文件名开头的序号（03-索引与事务排障.md → 3）；解析失败返回极大值，避免误判相邻。 */
    private int fileNumber(String filePath) {
        if (filePath == null) {
            return Integer.MAX_VALUE;
        }
        String name = filePath.substring(filePath.lastIndexOf('/') + 1);
        int i = 0;
        while (i < name.length() && Character.isDigit(name.charAt(i))) {
            i++;
        }
        if (i == 0) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(name.substring(0, i));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /** 标题匹配：完整包含优先；长词允许用末两字回退（执行计划里→执行计划），与标题召回同口径。 */
    private boolean titleContains(String title, String token) {        if (title == null || token == null || token.isBlank()) {
            return false;
        }
        String lowerTitle = title.toLowerCase(Locale.ROOT);
        String needle = token.toLowerCase(Locale.ROOT);
        if (lowerTitle.contains(needle)) {
            return true;
        }
        return needle.length() >= 3 && lowerTitle.contains(needle.substring(needle.length() - 2));
    }

    /**
     * 精确标题召回：仅当查询文本中完整出现某篇文档标题时命中；最多取 finalTopK 篇。
     * 关系来自 Markdown 标题本身，不做模糊相似度，避免把“标题里恰好有同名词”的文档误当引用。
     */
    private List<KbChunkHit> exactTitleHits(List<String> libraryIds, String query,
                                            java.util.Set<Long> filterDocIds, int limit) {
        if (query == null || query.isBlank() || libraryIds == null || libraryIds.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        int max = Math.max(1, Math.min(4, limit));
        List<KnowledgeDocumentEntity> docs;
        try {
            docs = documentRepository.findTitleMentioned(libraryIds, query, PageRequest.of(0, max));
        } catch (Exception e) {
            log.warn("精确标题召回失败，跳过该路 - err: {}", e.getMessage());
            return java.util.Collections.emptyList();
        }
        List<KbChunkHit> hits = new ArrayList<>();
        for (KnowledgeDocumentEntity doc : docs) {
            if (filterDocIds != null && !filterDocIds.contains(doc.getId())) {
                continue;
            }
            KnowledgeChunkEntity chunk = chunkRepository.findFirstByDocIdOrderByChunkIndexAsc(doc.getId()).orElse(null);
            if (chunk == null) {
                continue;
            }
            hits.add(KbChunkHit.builder()
                    .docId(doc.getId())
                    .chunkIndex(chunk.getChunkIndex())
                    .title(doc.getTitle())
                    .filePath(doc.getFilePath())
                    .siteSlug(doc.getSiteSlug())
                    .headingPath(chunk.getHeadingPath())
                    .heading(headingFromPath(chunk.getHeadingPath()))
                    .anchor(chunk.getAnchor())
                    .headingOrdinal(chunk.getHeadingOrdinal())
                    .chunkType(chunk.getChunkType())
                    .codeLang(chunk.getCodeLang())
                    .tableHeader(chunk.getTableHeader())
                    .text(chunk.getText())
                    .score(TITLE_MATCH_BOOST)
                    .source("title")
                    .build());
            if (hits.size() >= max) {
                break;
            }
        }
        return hits;
    }

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
        // 逐词条保留 run，再轮转合并：多概念查询里每个概念至少贡献一个靠前分块，
        // 避免“同时命中常见词”的分块把两个概念各自唯一的正确文档挤出 topK。
        List<List<String>> runKeys = new ArrayList<>();
        java.util.Map<String, KbChunkHit> hitByKey = new java.util.LinkedHashMap<>();
        java.util.Map<String, Integer> hitCounts = new java.util.LinkedHashMap<>();
        for (String libraryId : libraryIds) {
            if (libraryId == null || libraryId.isBlank()) {
                continue;
            }
            for (String token : tokens) {
                // 长中文短语在 LIKE 里等于全表扫描且命中率极低（如“索引存在却仍走全表扫描”）；
                // Redis BM25 路仍保留全部词条，MySQL 回退只查短词，避免把 P95 拖到秒级。
                if (token.length() > 8) {
                    continue;
                }
                List<KnowledgeChunkEntity> chunks;
                try {
                    chunks = chunkRepository.keywordSearch(libraryId, token, PageRequest.of(0, k));
                } catch (Exception e) {
                    log.warn("关键词检索失败，跳过词条 - token: {}, err: {}", token, e.getMessage());
                    continue;
                }
                List<String> keys = new ArrayList<>();
                for (KnowledgeChunkEntity chunk : chunks) {
                    KnowledgeDocumentEntity doc = docById(chunk.getDocId());
                    if (doc == null) {
                        continue;
                    }
                    String key = chunk.getDocId() + "#" + chunk.getChunkIndex();
                    hitByKey.putIfAbsent(key, KbChunkHit.builder()
                            .docId(chunk.getDocId())
                            .chunkIndex(chunk.getChunkIndex())
                            .title(doc.getTitle())
                            .filePath(doc.getFilePath())
                            .siteSlug(doc.getSiteSlug())
                            .headingPath(chunk.getHeadingPath())
                            .heading(headingFromPath(chunk.getHeadingPath()))
                            .anchor(chunk.getAnchor())
                            .headingOrdinal(chunk.getHeadingOrdinal())
                            .chunkType(chunk.getChunkType())
                            .codeLang(chunk.getCodeLang())
                            .text(chunk.getText())
                            .source(RESULT_SOURCE_KEYWORD)
                            .build());
                    hitCounts.merge(key, 1, Integer::sum);
                    keys.add(key);
                }
                runKeys.add(keys);
            }
        }
        java.util.LinkedHashMap<String, KbChunkHit> merged = new java.util.LinkedHashMap<>();
        // 多词条共同命中的分块先入队：最高置信度信号优先，轮转只负责补足单概念覆盖。
        hitCounts.entrySet().stream()
                .filter(e -> e.getValue() >= 2)
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .forEach(e -> {
                    KbChunkHit hit = hitByKey.get(e.getKey());
                    if (hit != null && merged.size() < k) {
                        hit.setScore((double) e.getValue() * KEYWORD_MATCH_SCORE);
                        merged.put(e.getKey(), hit);
                    }
                });
        for (int rank = 0; merged.size() < k; rank++) {
            boolean progressed = false;
            for (List<String> keys : runKeys) {
                if (rank >= keys.size()) {
                    continue;
                }
                String key = keys.get(rank);
                if (merged.containsKey(key)) {
                    continue;
                }
                KbChunkHit hit = hitByKey.get(key);
                if (hit != null) {
                    hit.setScore((double) hitCounts.getOrDefault(key, 1) * KEYWORD_MATCH_SCORE);
                    merged.put(key, hit);
                    progressed = true;
                }
                if (merged.size() >= k) {
                    break;
                }
            }
            if (!progressed) {
                break;
            }
        }
        return new java.util.ArrayList<>(merged.values());
    }

    /**
     * 文档标题命中加权：同一路召回内按“标题是否包含查询词条”重排。
     *
     * <p>RRF 只使用顺序、不使用原始分数，因此这里必须真正排序；技术文档标题通常是
     * 用户问题的最强意图信号，标题命中优先于正文中偶然出现的同名词。</p>
     */
    private void applyTitleBoost(List<KbChunkHit> hits, List<String> tokens) {
        if (hits == null || hits.isEmpty() || tokens == null || tokens.isEmpty()) {
            return;
        }
        for (KbChunkHit hit : hits) {
            String title = hit.getTitle();
            if (title == null || title.isBlank()) {
                continue;
            }
            boolean titleMatched = tokens.stream()
                    .filter(t -> t != null && t.length() >= 2)
                    .anyMatch(t -> title.toLowerCase().contains(t.toLowerCase()));
            if (titleMatched) {
                hit.setScore((hit.getScore() == null ? 0d : hit.getScore()) + TITLE_MATCH_BOOST);
            }
        }
        hits.sort(Comparator
                .comparing((KbChunkHit h) -> h.getScore() == null ? 0d : h.getScore()).reversed()
                .thenComparing(KbChunkHit::getDocId)
                .thenComparing(KbChunkHit::getChunkIndex));
    }

    /**
     * 轻量分词：按空白与常用中英文标点切分，保留技术术语（API 名、错误码、类名）。
     * 停用标点被切掉，即配置中"去除停用标点、保留词条"的语义。
     * 为避免过多词条导致多余的数据库查询与噪声，限制词条数上限、丢弃单字符噪声词条。
     */
    /** 关键词词条上限：多概念问题常有两个实体，5 会在第二个实体前截断；8 兼顾覆盖与 BM25 开销。 */
    private static final int MAX_KEYWORD_TOKENS = 8;
    /** 单概念查询保持 5 个词，避免无谓的 BM25/LIKE 查询把 P95 拉高。 */
    private static final int MAX_KEYWORD_TOKENS_SINGLE = 6;

    private List<String> tokenize(String query) {
        String text = query == null ? "" : query.trim();
        if (text.isEmpty()) {
            return new ArrayList<>();
        }
        // 中文技术问题常见“的/怎么/有哪些”等虚词会把关键词粘成一个长串，
        // 导致 LIKE/BM25 无法命中正文里的“扩容”“自动配置”等核心词；先剥离问句壳再切词。
        // 多概念问句才放宽词条上限：单概念标题查询保持 5，避免 8 条 LIKE/BM25 查询拖高 P95。
        int tokenLimit = isMultiIntentQuery(text) ? MAX_KEYWORD_TOKENS : MAX_KEYWORD_TOKENS_SINGLE;
        text = text.replaceAll(
                "(是怎样的|是什么|有哪些|哪几种|有什么区别|的区别|是怎么实现的|怎么实现|如何实现|为什么|怎么样|怎么|如何|请问|请解释|解释一下|由哪些部分组成|一下|如果|遇到|应该|按什么|会发生什么)",
                " ");
        text = text.replaceAll("的", " ");
        // 复合词尾部（机制/原理/流程等）拆开，保留“扩容/自动配置”等真正的主题词。
        text = text.replaceAll("(机制|原理|流程|步骤|方式|方法|作用|实现|优化|结构|算法|属性|状态|类型)$", " $1");
        // 问号/感叹号必须作为分隔符：否则“代表什么？如果索引存在…”会粘成一个 10+ 字长词，
        // LIKE 退化为全表扫描且几乎不可能命中，既慢又丢召回。
        String[] parts = text.split("[\\s,，。;；、:：？?！!\\-—_/|()（）]+");
        java.util.LinkedHashSet<String> tokens = new java.util.LinkedHashSet<>();
        for (String p : parts) {
            String t = sanitizeKeyword(p);
            // 丢弃单字符噪声词条（如单个字/符号），避免 LIKE/护词条数上限
            if (t.length() >= 2) {
                tokens.add(t);
                if (tokens.size() >= tokenLimit) {
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

    /** 多概念判定：至少两个问号/分号，或出现并列“和/与”。单问号结尾不算，保持单跳查询词条数不变。 */
    private boolean isMultiIntentQuery(String text) {
        if (text == null) {
            return false;
        }
        int marks = 0;
        for (char c : text.toCharArray()) {
            if (c == '?' || c == '？' || c == ';' || c == '；') {
                marks++;
            }
        }
        return marks >= 2 || text.contains("和") || text.contains("与");
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
