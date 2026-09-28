package org.example.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.KbChunkHit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import redis.clients.jedis.JedisPooled;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * RediSearch BM25 真实集成验证（需本地 Redis Stack）：
 * 创建中文分词索引 → 写入 JSON 文档 → 中文词检索命中。
 * Redis Stack 未运行时自动跳过（assumeTrue），不阻断常规构建。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisBm25KeywordSearchRealTest {

    private static final String INDEX = "kb_bm25_it";
    private static final String PREFIX = "ittest:bm25:";

    private static JedisPooled jedis;
    private static RedisBm25KeywordSearch searcher;

    @BeforeAll
    static void setUp() {
        try {
            jedis = new JedisPooled("127.0.0.1", 6379);
            assumeTrue("PONG".equals(jedis.ping()), "本地 Redis Stack 未运行，跳过集成验证");
        } catch (Exception e) {
            jedis = null;
            assumeTrue(false, "本地 Redis Stack 未运行，跳过集成验证: " + e.getMessage());
        }
        searcher = new RedisBm25KeywordSearch(jedis, config(), new ObjectMapper());
    }

    private static org.example.config.KbConfig config() {
        // KbConfig 是 @Configuration + @Value 字段，测试内手动注入所需值
        org.example.config.KbConfig cfg = new org.example.config.KbConfig();
        org.springframework.test.util.ReflectionTestUtils.setField(cfg, "bm25IndexName", INDEX);
        org.springframework.test.util.ReflectionTestUtils.setField(cfg, "redisPrefix", PREFIX);
        return cfg;
    }

    @BeforeEach
    void resetFixture() {
        dropFixture();
        searcher.ensureIndex(INDEX, PREFIX);
        putDoc(PREFIX + "1",
                "{\"text\":\"React 组件的状态管理与性能优化实践\",\"docId\":10,\"chunkIndex\":0,\"title\":\"React 文档\",\"filePath\":\"前端/react.md\",\"siteSlug\":\"react\"}");
        putDoc(PREFIX + "2",
                "{\"text\":\"Spring Bean 的生命周期与循环依赖解决方案\",\"docId\":20,\"chunkIndex\":1,\"title\":\"Spring 文档\",\"filePath\":\"后端/spring.md\",\"siteSlug\":\"spring\"}");
        putDoc(PREFIX + "3",
                "{\"text\":\"Docker 容器网络与端口映射\",\"docId\":30,\"chunkIndex\":0,\"title\":\"Docker 文档\",\"filePath\":\"运维/docker.md\",\"siteSlug\":\"docker\"}");
    }

    /** 以 JSON.SET 原始字符串写入文档（模拟 RedisEmbeddingStore 的 JSON 结构）。 */
    private static void putDoc(String key, String json) {
        jedis.jsonSetWithPlainString(key, redis.clients.jedis.json.Path.ROOT_PATH, json);
    }

    @AfterAll
    static void tearDown() {
        if (jedis == null) {
            return;
        }
        dropFixture();
        jedis.close();
    }

    private static void dropFixture() {
        try {
            jedis.ftDropIndex(INDEX);
        } catch (Exception ignored) {
            // 首次运行索引不存在
        }
        for (int i = 1; i <= 3; i++) {
            jedis.del(PREFIX + i);
        }
    }

    @Test
    @Order(1)
    void ensureIndexIsIdempotent() {
        searcher.ensureIndex(INDEX, PREFIX);
        searcher.ensureIndex(INDEX, PREFIX);
    }

    @Test
    @Order(2)
    void chineseTermHitsExpectedDocument() throws InterruptedException {
        // 索引异步构建：轮询等待文档可检索
        waitForIndexed(3);

        List<KbChunkHit> hits = searcher.search(INDEX, List.of("状态管理"), 5);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).getDocId()).isEqualTo(10L);
        assertThat(hits.get(0).getTitle()).isEqualTo("React 文档");
        assertThat(hits.get(0).getSource()).isEqualTo("keyword");
    }

    @Test
    @Order(3)
    void unrelatedTermMissesAndReturnsEmpty() throws InterruptedException {
        waitForIndexed(3);

        List<KbChunkHit> hits = searcher.search(INDEX, List.of("区块链"), 5);

        assertThat(hits).isEmpty();
    }

    private static void waitForIndexed(int expectedDocs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                long total = jedis.ftSearch(INDEX, "*").getTotalResults();
                if (total >= expectedDocs) {
                    return;
                }
            } catch (Exception ignored) {
                // 索引尚未就绪
            }
            Thread.sleep(200);
        }
    }
}
