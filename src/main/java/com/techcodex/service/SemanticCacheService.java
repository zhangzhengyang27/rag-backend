package com.techcodex.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import com.techcodex.dto.KbChunkHit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.search.Document;
import redis.clients.jedis.search.FTCreateParams;
import redis.clients.jedis.search.IndexDataType;
import redis.clients.jedis.search.Query;
import redis.clients.jedis.search.SearchResult;
import redis.clients.jedis.search.schemafields.NumericField;
import redis.clients.jedis.search.schemafields.SchemaField;
import redis.clients.jedis.search.schemafields.TagField;
import redis.clients.jedis.search.schemafields.TextField;
import redis.clients.jedis.search.schemafields.VectorField;
import redis.clients.jedis.json.Path;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 语义缓存（P1-13/14）。
 *
 * <p>两级命中：先按「问题 + 检索上下文」做 SHA-256 精确命中，再查 Redis 向量索引近似命中。
 * 缓存按 library 隔离，并用版本号实现文档变更失效：文档更新/删除后版本 +1，旧缓存自然不再命中，
 * 不需要遍历删除几十万条缓存。</p>
 *
 * <p>边界：向量索引只在首次访问时幂等创建；Redis 不可用/索引未就绪时全部降级为 miss，
 * 不影响问答主链路。缓存只保存回答文本与 sources JSON，不保存用户身份。</p>
 */
@Slf4j
@Service
public class SemanticCacheService {

    private static final String KEY_PREFIX = "kb:semantic:cache:";
    private static final String EXACT_PREFIX = "kb:semantic:exact:";
    private static final String VERSION_PREFIX = "kb:semantic:ver:";
    private static final String INDEX_NAME = "kb_semantic_cache";
    private static final int VECTOR_DIM = 512;

    private final JedisPooled jedis;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper;

    @Value("${app.kb.cache.enabled:true}")
    private boolean enabled;

    @Value("${app.kb.cache.ttl-seconds:604800}")
    private long ttlSeconds;

    @Value("${app.kb.cache.semantic-threshold:0.92}")
    private double semanticThreshold;

    private volatile boolean indexReady = false;

    public SemanticCacheService(JedisPooled kbJedis, EmbeddingModel embeddingModel, ObjectMapper objectMapper) {
        this.jedis = kbJedis;
        this.embeddingModel = embeddingModel;
        this.objectMapper = objectMapper;
    }

    public record Hit(String answer, List<KbChunkHit> sources, double similarity) {
    }

    /** 精确命中 → 向量近似命中；任何异常都返回 empty，主链路继续正常 RAG。 */
    public Optional<Hit> lookup(String question, String libraryId, String context) {
        if (!enabled || question == null || question.isBlank()) return Optional.empty();
        try {
            long version = currentVersion(libraryId);
            String exactKey = EXACT_PREFIX + hash(normalize(question) + "|" + libraryId + "|" + context);
            String exact = jedis.get(exactKey);
            if (exact != null) {
                CacheEntry entry = objectMapper.readValue(exact, CacheEntry.class);
                if (entry.version == version && libraryId.equals(entry.libraryId)) {
                    return Optional.of(new Hit(entry.answer, parseSources(entry.sources), 1.0));
                }
            }
            if (!ensureIndex()) return Optional.empty();
            float[] vector = embedding(question);
            byte[] bytes = toBytes(vector);
            Query query = new Query("@library:{ " + escapeTag(libraryId) + " } @version:[" + version + " " + version + "]"
                    + "=>[KNN 1 @embedding $vec AS score]")
                    .addParam("vec", bytes)
                    .returnFields("question", "answer", "sources", "library", "version", "score")
                    .setSortBy("score", true)
                    .limit(0, 1)
                    .dialect(2);
            SearchResult result = jedis.ftSearch(INDEX_NAME, query);
            if (result.getDocuments().isEmpty()) return Optional.empty();
            Document doc = result.getDocuments().get(0);
            double distance = parseDouble(doc.get("score"));
            double similarity = 1.0 - distance;
            if (similarity < semanticThreshold) return Optional.empty();
            String answer = doc.getString("answer");
            String sourcesJson = doc.getString("sources");
            if (answer == null || answer.isBlank()) return Optional.empty();
            return Optional.of(new Hit(answer, parseSources(sourcesJson), similarity));
        } catch (Exception e) {
            log.debug("语义缓存查询失败，降级为正常 RAG - library: {}, err: {}", libraryId, e.getMessage());
            return Optional.empty();
        }
    }

    /** 缓存回答与 sources；空回答/异常不写入。 */
    public void put(String question, String libraryId, String context, String answer, List<KbChunkHit> sources) {
        if (!enabled || question == null || question.isBlank() || answer == null || answer.isBlank()) return;
        if (answer.length() < 20) return;
        try {
            long version = currentVersion(libraryId);
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("question", question);
            doc.put("answer", answer);
            doc.put("sources", objectMapper.writeValueAsString(sources == null ? List.of() : sources));
            doc.put("library", libraryId);
            doc.put("context", context);
            doc.put("version", version);
            doc.put("updatedAt", System.currentTimeMillis());
            doc.put("embedding", toList(embedding(question)));
            String vectorKey = KEY_PREFIX + hash(normalize(question) + "|" + libraryId + "|" + context);
            jedis.jsonSet(vectorKey, Path.ROOT_PATH, doc);
            jedis.expire(vectorKey, ttlSeconds);
            CacheEntry entry = new CacheEntry();
            entry.answer = answer;
            entry.sources = (String) doc.get("sources");
            entry.libraryId = libraryId;
            entry.context = context;
            entry.version = version;
            String exactKey = EXACT_PREFIX + hash(normalize(question) + "|" + libraryId + "|" + context);
            jedis.setex(exactKey, ttlSeconds, objectMapper.writeValueAsString(entry));
            ensureIndex();
        } catch (Exception e) {
            log.debug("语义缓存写入失败 - library: {}, err: {}", libraryId, e.getMessage());
        }
    }

    /** 文档变更后版本 +1；旧缓存因 version 不匹配自动失效。 */
    public void invalidate(String libraryId) {
        try {
            jedis.incr(VERSION_PREFIX + libraryId);
        } catch (Exception e) {
            log.warn("语义缓存版本失效失败 - library: {}, err: {}", libraryId, e.getMessage());
        }
    }

    private long currentVersion(String libraryId) {
        String key = VERSION_PREFIX + libraryId;
        String v = jedis.get(key);
        if (v == null) {
            jedis.set(key, "1");
            return 1L;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 1L;
        }
    }

    private boolean ensureIndex() {
        if (indexReady) return true;
        synchronized (this) {
            if (indexReady) return true;
            try {
                jedis.ftInfo(INDEX_NAME);
                indexReady = true;
                return true;
            } catch (Exception ignored) {
                // 不存在则创建
            }
            try {
                List<SchemaField> schema = List.of(
                        TextField.of("$.question").as("question"),
                        TextField.of("$.answer").as("answer"),
                        TextField.of("$.sources").as("sources"),
                        TagField.of("$.library").as("library"),
                        NumericField.of("$.version").as("version"),
                        NumericField.of("$.updatedAt").as("updatedAt"),
                        VectorField.builder()
                                .fieldName("$.embedding")
                                .as("embedding")
                                .algorithm(VectorField.VectorAlgorithm.FLAT)
                                .addAttribute("TYPE", "FLOAT32")
                                .addAttribute("DIM", VECTOR_DIM)
                                .addAttribute("DISTANCE_METRIC", "COSINE")
                                .build()
                );
                FTCreateParams params = FTCreateParams.createParams()
                        .on(IndexDataType.JSON)
                        .addPrefix(KEY_PREFIX)
                        .language("chinese");
                jedis.ftCreate(INDEX_NAME, params, schema);
                indexReady = true;
                log.info("语义缓存索引已创建 - index: {}", INDEX_NAME);
                return true;
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
                if (msg.contains("index already exists")) {
                    indexReady = true;
                    return true;
                }
                log.warn("语义缓存索引创建失败，缓存降级为 miss - {}", e.getMessage());
                return false;
            }
        }
    }

    private float[] embedding(String text) {
        Embedding embedding = embeddingModel.embed(text).content();
        return embedding.vector();
    }

    private List<KbChunkHit> parseSources(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<KbChunkHit>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String normalize(String q) {
        return q == null ? "" : q.trim().replaceAll("\\s+", " ").toLowerCase();
    }

    private static String hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private static String escapeTag(String value) {
        // RediSearch tag 值内的空格/特殊字符转义；libraryId 实际是 [a-zA-Z0-9_-]，保留兜底
        return value.replaceAll("([^a-zA-Z0-9_\\-])", "\\\\$1");
    }

    private static byte[] toBytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : vector) buffer.putFloat(v);
        return buffer.array();
    }

    private static List<Float> toList(float[] vector) {
        List<Float> list = new ArrayList<>(vector.length);
        for (float v : vector) list.add(v);
        return list;
    }

    private static double parseDouble(Object v) {
        if (v == null) return 2.0;
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 2.0;
        }
    }

    public static class CacheEntry {
        public String answer;
        public String sources;
        public String libraryId;
        public String context;
        public long version;

        public CacheEntry() {}
    }
}
