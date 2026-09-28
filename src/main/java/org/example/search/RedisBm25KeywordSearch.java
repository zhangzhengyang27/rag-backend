package org.example.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.config.KbConfig;
import org.example.dto.KbChunkHit;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.search.Document;
import redis.clients.jedis.search.FTCreateParams;
import redis.clients.jedis.search.IndexDataType;
import redis.clients.jedis.search.Query;
import redis.clients.jedis.search.SearchResult;
import redis.clients.jedis.search.schemafields.SchemaField;
import redis.clients.jedis.search.schemafields.TextField;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 RediSearch 的 BM25 关键词检索（混合检索的关键词一路）。
 *
 * <p>为什么不直接查现有 {@code kb_embeddings} 索引：该索引未声明语言（默认 english），
 * 中文按标点/空白切词，整句中文会成为单个词元，词级匹配几乎全部落空。本类创建并维护
 * 一个同前缀的补充索引，显式声明 {@code LANGUAGE chinese}（内置中文分词），查询侧同样
 * 以 chinese 分词。英文索引下中文词查询命中为 0 已实测验证。</p>
 *
 * <p>命中直接从 Redis JSON 文档构造（索引时已写入 docId/chunkIndex/title/filePath/
 * siteSlug/text 元数据），不依赖 kb_chunk.vector_id 回查；空间过滤由检索主链路的
 * filterDocIds 统一兜底（与向量路一致）。任一异常向调用方抛出，由其回退 MySQL LIKE。</p>
 *
 * <p>词条累加口径与 MySQL 路一致：同一分块命中多个词条时置信度 +1，最终按累计分数
 * 降序截断。</p>
 */
@Slf4j
@Component
public class RedisBm25KeywordSearch {

    /** 命中来源标识（与 KbSearchServiceImpl 的关键词路一致） */
    public static final String SOURCE_KEYWORD = "keyword";

    /** 只取构造命中所需字段：默认返回会带上 512 维 vector，单次检索多传数百 KB */
    private static final String[] RETURN_FIELDS = {
            "$.docId", "$.chunkIndex", "$.title", "$.filePath", "$.siteSlug", "$.text"};

    private final JedisPooled jedis;
    private final KbConfig kbConfig;
    private final ObjectMapper objectMapper;

    public RedisBm25KeywordSearch(JedisPooled kbJedis, KbConfig kbConfig, ObjectMapper objectMapper) {
        this.jedis = kbJedis;
        this.kbConfig = kbConfig;
        this.objectMapper = objectMapper;
    }

    /** 幂等创建按配置的 BM25 补充索引（中文分词）。索引已存在时静默跳过。 */
    public void ensureConfiguredIndex() {
        ensureIndex(kbConfig.getBm25IndexName(), kbConfig.getRedisPrefix());
    }

    /** 幂等创建 BM25 补充索引（中文分词）；已存在时跳过，其余异常向上抛出。 */
    public void ensureIndex(String indexName, String prefix) {
        // schemafields API 配套 FTCreateParams（jedis 7 的 FT.CREATE 新接口）
        FTCreateParams params = FTCreateParams.createParams()
                .on(IndexDataType.JSON)
                .addPrefix(prefix)
                .language("chinese");
        List<SchemaField> schema = List.of(TextField.of("$.text").as("text"));
        try {
            String result = jedis.ftCreate(indexName, params, schema);
            log.info("BM25 关键词索引已创建 - index: {}, prefix: {}, result: {}", indexName, prefix, result);
        } catch (Exception e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.toLowerCase().contains("index already exists")) {
                log.info("BM25 关键词索引已存在，跳过创建 - index: {}", indexName);
                return;
            }
            throw e;
        }
    }

    /**
     * BM25 关键词检索：逐词条查询（查询侧中文分词），同一分块多词条命中累加置信度。
     *
     * @param tokens 已分词的查询词条（建议 ≤4 个）
     * @param k      每词条召回上限与最终返回条数
     * @return 按累计分数降序的命中；无命中返回空列表（调用方回退 MySQL LIKE）
     */
    public List<KbChunkHit> search(List<String> tokens, int k) {
        return search(kbConfig.getBm25IndexName(), tokens, k);
    }

    public List<KbChunkHit> search(String indexName, List<String> tokens, int k) {
        LinkedHashMap<String, KbChunkHit> merged = new LinkedHashMap<>();
        for (String token : tokens) {
            String term = toQueryTerm(token);
            if (term.isEmpty()) {
                continue;
            }
            // 不加引号：带引号的短语查询与中文分词组合时行为不可靠（实测），裸词条由
            // 查询侧 chinese 分词后默认取交集，语义与多词条召回一致
            Query query = new Query("@text:(" + term + ")")
                    .setLanguage("chinese")
                    .limit(0, Math.max(1, k))
                    .returnFields(RETURN_FIELDS);
            SearchResult result = jedis.ftSearch(indexName, query);
            for (Document doc : result.getDocuments()) {
                RedisChunkDoc payload = parseChunkDoc(doc);
                if (payload == null || payload.docId() == null || payload.chunkIndex() == null) {
                    continue;
                }
                String key = payload.docId() + "#" + payload.chunkIndex();
                KbChunkHit hit = merged.get(key);
                if (hit == null) {
                    merged.put(key, KbChunkHit.builder()
                            .docId(payload.docId())
                            .chunkIndex(payload.chunkIndex())
                            .title(defaultEmpty(payload.title()))
                            .filePath(defaultEmpty(payload.filePath()))
                            .siteSlug(defaultEmpty(payload.siteSlug()))
                            .text(defaultEmpty(payload.text()))
                            .score(1.0)
                            .source(SOURCE_KEYWORD)
                            .build());
                } else {
                    // 多词条命中同一分块：累加置信度（与 MySQL 路口径一致）
                    hit.setScore((hit.getScore() == null ? 0 : hit.getScore()) + 1.0);
                }
            }
        }
        return merged.values().stream()
                .sorted(Comparator.comparing((KbChunkHit h) -> h.getScore() == null ? 0d : h.getScore()).reversed()
                        .thenComparing(KbChunkHit::getDocId)
                        .thenComparing(KbChunkHit::getChunkIndex))
                .limit(Math.max(1, k))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    /**
     * 词条 → 查询词元：仅保留字母/数字（含 CJK），其余字符折叠为空格。
     * 去除 RediSearch 查询语法字符（| - @ " 等）避免被当作操作符；产生的多个词
     * 在 @text:(...) 内为交集语义。
     */
    static String toQueryTerm(String token) {
        if (token == null) {
            return "";
        }
        return token.replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /** 从 FT.SEARCH（RETURN 别名路径）返回的属性解析分块元数据；结构异常返回 null（跳过该命中）。 */
    private RedisChunkDoc parseChunkDoc(Document doc) {
        Iterable<Map.Entry<String, Object>> properties = doc.getProperties();
        if (properties == null) {
            return null;
        }
        Long docId = null;
        Integer chunkIndex = null;
        String title = null;
        String filePath = null;
        String siteSlug = null;
        String text = null;
        for (Map.Entry<String, Object> entry : properties) {
            switch (entry.getKey() == null ? "" : entry.getKey()) {
                case "$.docId" -> docId = asLong(entry.getValue());
                case "$.chunkIndex" -> chunkIndex = asInt(entry.getValue());
                case "$.title" -> title = asString(entry.getValue());
                case "$.filePath" -> filePath = asString(entry.getValue());
                case "$.siteSlug" -> siteSlug = asString(entry.getValue());
                case "$.text" -> text = asString(entry.getValue());
                default -> {
                    // 忽略未知字段
                }
            }
        }
        if (docId == null || docId < 0 || chunkIndex == null || chunkIndex < 0) {
            return null;
        }
        return new RedisChunkDoc(docId, chunkIndex, title, filePath, siteSlug, text);
    }

    /** RETURN 返回值为 JSON 编码字符串（数字裸值、字符串带引号），统一转 Long。 */
    private Long asLong(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(String.valueOf(raw));
            return node.isValueNode() ? node.asLong(-1) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Integer asInt(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(String.valueOf(raw));
            return node.isValueNode() ? node.asInt(-1) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String asString(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw);
        try {
            JsonNode node = objectMapper.readTree(value);
            return node.isValueNode() ? node.asText(null) : value;
        } catch (Exception e) {
            // 个别场景返回原始字符串（非 JSON 编码），直接使用
            return value;
        }
    }

    private String defaultEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Redis JSON 文档中的分块元数据（与 KbIndexer 写入的 meta 对应）。 */
    private record RedisChunkDoc(Long docId, Integer chunkIndex, String title,
                                 String filePath, String siteSlug, String text) {
    }
}
