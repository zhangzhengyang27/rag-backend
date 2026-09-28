package org.example.config;

import dev.langchain4j.community.store.embedding.redis.RedisEmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.model.scoring.onnx.OnnxScoringModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.search.schemafields.NumericField;
import redis.clients.jedis.search.schemafields.SchemaField;
import redis.clients.jedis.search.schemafields.TextField;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 知识库 RAG 独立配置：为知识库分配专用 Redis 向量索引与检索器，与其余向量数据隔离。
 * <ul>
 *   <li>向量化模型复用 {@link RagConfig} 提供的本地 ONNX 模型；</li>
 *   <li>索引名/前缀/维度/分块/检索参数统一由 {@code app.kb.*} 配置注入。</li>
 * </ul>
 */
@Slf4j
@Configuration
public class KbConfig {

    @Value("${app.kb.redis.host:127.0.0.1}")
    private String redisHost;

    @Value("${app.kb.redis.port:6379}")
    private int redisPort;

    @Value("${app.kb.redis.password:}")
    private String redisPassword;

    @Value("${app.kb.redis.index-name:kb_embeddings}")
    private String redisIndexName;

    @Value("${app.kb.redis.prefix:lostsystem:kbdoc:}")
    private String redisPrefix;

    /** BM25 关键词补充索引名（中文分词，见 RedisBm25KeywordSearch） */
    @Value("${app.kb.retrieval.bm25-index-name:kb_embeddings_bm25}")
    private String bm25IndexName;

    // 默认对齐本地 ONNX 模型 bge-small-zh-v15 的维度 512，避免遗漏配置时因维数不匹配报错
    @Value("${app.kb.embedding-dimension:512}")
    private int embeddingDimension;

    @Value("${app.kb.chunk.max-segment-size:500}")
    private int chunkMaxSegmentSize;

    @Value("${app.kb.chunk.max-overlap-size:60}")
    private int chunkMaxOverlapSize;

    @Value("${app.kb.retrieval.max-results:4}")
    private int retrievalMaxResults;

    @Value("${app.kb.retrieval.min-score:0.4}")
    private double retrievalMinScore;

    // ---------- 混合检索参数 ----------
    @Value("${app.kb.retrieval.vector-top-n:20}")
    private int vectorTopN;

    @Value("${app.kb.retrieval.bm25-top-n:20}")
    private int bm25TopN;

    @Value("${app.kb.retrieval.rrf-k:60}")
    private int rrfK;

    @Value("${app.kb.retrieval.final-top-k:6}")
    private int finalTopK;

    @Value("${app.kb.retrieval.bm25-enabled:true}")
    private boolean bm25Enabled;

    @Value("${app.kb.retrieval.query-rewrite-enabled:true}")
    private boolean queryRewriteEnabled;

    @Value("${app.kb.retrieval.query-rewrite-timeout-ms:3000}")
    private long queryRewriteTimeoutMs;

    // ---------- 语义重排参数 ----------
    @Value("${app.kb.retrieval.rerank-enabled:false}")
    private boolean rerankEnabled;

    @Value("${app.kb.retrieval.rerank-model-path:}")
    private String rerankModelPath;

    @Value("${app.kb.retrieval.rerank-tokenizer-path:}")
    private String rerankTokenizerPath;

    @Value("${app.kb.retrieval.rerank-top-n:10}")
    private int rerankTopN;

    // ---------- 意图路由 + 相关度判定（RelevanceChecker 拒答） ----------
    /** 是否启用意图路由：非知识类问题（寒暄/指令/感谢）跳过知识库检索，直接普通对话 */
    @Value("${app.kb.retrieval.intent-routing-enabled:true}")
    private boolean intentRoutingEnabled;

    /** 是否启用相关度拒答：检索结果最高分低于阈值时拒绝基于资料硬答 */
    @Value("${app.kb.retrieval.relevance-check-enabled:true}")
    private boolean relevanceCheckEnabled;

    /** 相关度阈值：融合后最高命中分数低于该值即判定"知识库无相关内容" */
    @Value("${app.kb.retrieval.relevance-min-score:0.45}")
    private double relevanceMinScore;

    // ---------- 多空间默认配置 ----------
    @Value("${app.kb.library.public-code:default}")
    private String defaultLibrary;

    @Value("${app.library.default-doc-limit:5000}")
    private long defaultDocLimit;

    @Value("${app.library.default-retrieval-limit:2000}")
    private long defaultRetrievalLimit;

    @Value("${app.library.default-chat-limit:1000}")
    private long defaultChatLimit;

    // ---------- 自助空间（个人/团队）默认配额：面向 C 端用户，独立于平台默认值 ----------
    @Value("${app.kb.library.user-doc-limit:200}")
    private long selfServiceDocLimit;

    @Value("${app.kb.library.user-retrieval-limit:200}")
    private long selfServiceRetrievalLimit;

    @Value("${app.kb.library.user-chat-limit:100}")
    private long selfServiceChatLimit;

    // ---------- 内容安全（默认值与 application.properties 的 app.kb.safety.* 对应，
    //            并作为运行时配置 safety.* 的回退默认，保证配置链不断裂） ----------
    @Value("${app.kb.safety.enabled:true}")
    private boolean safetyEnabled;

    @Value("${app.kb.safety.sensitive-words:}")
    private String safetySensitiveWords;

    // ---------- 评测告警阈值（运行时配置 eval.hitrate-threshold 的回退默认） ----------
    @Value("${app.kb.eval.hitrate-threshold:0.6}")
    private double evalHitrateThreshold;

    /**
     * 知识库专用向量存储：独立索引，向量数据持久化，重启不丢失。
     */
    /**
     * 知识库专用 Redis 连接池（jedis 7.x，实现 UnifiedJedis）。
     * 由 Spring 管理生命周期，供 RedisEmbeddingStore 复用，避免每次检索新建连接。
     */
    @Bean(destroyMethod = "close")
    public JedisPooled kbJedis() {
        // 使用显式 JedisClientConfig 以放宽 socket 超时：
        // 重建索引时 RedisEmbeddingStore.removeAll() 需删除全部历史向量（5 万+ 条），
        // jedis 默认 2s socket 超时会导致删除操作 Read timed out。
        DefaultJedisClientConfig.Builder cfg = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(30000)
                .socketTimeoutMillis(120000);
        if (StringUtils.hasText(redisPassword)) {
            cfg.password(redisPassword);
        }
        return new JedisPooled(new HostAndPort(redisHost, redisPort), cfg.build());
    }

    @Bean
    public EmbeddingStore<TextSegment> kbEmbeddingStore(JedisPooled kbJedis) {
        RedisEmbeddingStore.Builder builder = RedisEmbeddingStore.builder()
                .unifiedJedis(kbJedis)
                .indexName(redisIndexName)
                .prefix(redisPrefix)
                .dimension(embeddingDimension);
        // 注册溯源元数据字段，否则 RedisEmbeddingStore.search 重建的 TextSegment
        // 不含 docId/chunkIndex/title/filePath，导致向量命中无法定位文档。
        // 注意：不要在这里注册 "text" 字段——RedisEmbeddingStore 会自动为向量原文添加
        // text 字段，重复注册会导致全新索引创建时报 "Duplicate field in schema"；
        // BM25 关键词检索使用独立的 kb_embeddings_bm25 索引，不依赖此处的 text 字段。
        Map<String, SchemaField> metadataConfig = new HashMap<>();
        metadataConfig.put("docId", NumericField.of("$.docId").as("docId"));
        metadataConfig.put("chunkIndex", NumericField.of("$.chunkIndex").as("chunkIndex"));
        metadataConfig.put("title", TextField.of("$.title").as("title"));
        metadataConfig.put("filePath", TextField.of("$.filePath").as("filePath"));
        builder.metadataConfig(metadataConfig);
        return builder.build();
    }

    /**
     * 语义重排模型（bge-reranker-base INT8 量化 ONNX，本地推理）。
     *
     * <p>仅当 {@code rerank-enabled=true} 且模型/tokenizer 文件均存在时才注册该 bean；
     * 任一条件不满足则返回 null（不阻断启动），由 {@code KbSearchServiceImpl} 通过
     * {@code @Autowired(required=false)} 注入为 null 并降级为纯 RRF 结果。</p>
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.kb.retrieval", name = "rerank-enabled", havingValue = "true")
    public ScoringModel rerankScoringModel() {
        if (!StringUtils.hasText(rerankModelPath) || !StringUtils.hasText(rerankTokenizerPath)) {
            log.warn("重排未启用：模型/tokenizer 路径未配置，跳过注册 ScoringModel");
            return null;
        }
        if (!new File(rerankModelPath).exists() || !new File(rerankTokenizerPath).exists()) {
            log.warn("重排模型文件缺失，降级为纯 RRF 检索 - model: {}, tokenizer: {}",
                    rerankModelPath, rerankTokenizerPath);
            return null;
        }
        try {
            OnnxScoringModel model = new OnnxScoringModel(rerankModelPath, rerankTokenizerPath);
            log.info("语义重排模型加载成功 - {}", rerankModelPath);
            return model;
        } catch (Exception e) {
            log.warn("语义重排模型加载失败，降级为纯 RRF 检索 - err: {}", e.getMessage());
            return null;
        }
    }

    public int getChunkMaxSegmentSize() {
        return chunkMaxSegmentSize;
    }

    public int getChunkMaxOverlapSize() {
        return chunkMaxOverlapSize;
    }

    public int getRetrievalMaxResults() {
        return retrievalMaxResults;
    }

    public double getRetrievalMinScore() {
        return retrievalMinScore;
    }

    public int getVectorTopN() {
        return vectorTopN;
    }

    public int getBm25TopN() {
        return bm25TopN;
    }

    public int getRrfK() {
        return rrfK;
    }

    public int getFinalTopK() {
        return finalTopK;
    }

    public boolean isBm25Enabled() {
        return bm25Enabled;
    }

    public boolean isQueryRewriteEnabled() {
        return queryRewriteEnabled;
    }

    public long getQueryRewriteTimeoutMs() {
        return queryRewriteTimeoutMs;
    }

    public boolean isRerankEnabled() {
        return rerankEnabled;
    }

    public int getRerankTopN() {
        return rerankTopN;
    }

    public boolean isIntentRoutingEnabled() {
        return intentRoutingEnabled;
    }

    public boolean isRelevanceCheckEnabled() {
        return relevanceCheckEnabled;
    }

    public double getRelevanceMinScore() {
        return relevanceMinScore;
    }

    public String getPublicLibrary() {
        return defaultLibrary;
    }

    public String getRedisIndexName() {
        return redisIndexName;
    }

    public String getRedisPrefix() {
        return redisPrefix;
    }

    public String getBm25IndexName() {
        return bm25IndexName;
    }

    public long getDefaultDocLimit() {
        return defaultDocLimit;
    }

    public long getDefaultRetrievalLimit() {
        return defaultRetrievalLimit;
    }

    public long getDefaultChatLimit() {
        return defaultChatLimit;
    }

    public long getSelfServiceDocLimit() {
        return selfServiceDocLimit;
    }

    public long getSelfServiceRetrievalLimit() {
        return selfServiceRetrievalLimit;
    }

    public long getSelfServiceChatLimit() {
        return selfServiceChatLimit;
    }

    public boolean isSafetyEnabled() {
        return safetyEnabled;
    }

    public String getSafetySensitiveWords() {
        return safetySensitiveWords;
    }

    public double getEvalHitrateThreshold() {
        return evalHitrateThreshold;
    }
}
