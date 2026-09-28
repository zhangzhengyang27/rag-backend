package org.example.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.KbChunkHit;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.search.Document;
import redis.clients.jedis.search.Query;
import redis.clients.jedis.search.SearchResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RediSearch BM25 检索单测：查询词清洗、多词条置信度累加、按分数排序截断、异常上抛。
 */
class RedisBm25KeywordSearchTest {

    private final JedisPooled jedis = mock(JedisPooled.class);
    private final org.example.config.KbConfig kbConfig = mock(org.example.config.KbConfig.class);

    private RedisBm25KeywordSearch newSearcher() {
        return new RedisBm25KeywordSearch(jedis, kbConfig, new ObjectMapper());
    }

    @Test
    void toQueryTermKeepsLettersAndDigitsOnly() {
        assertThat(RedisBm25KeywordSearch.toQueryTerm("状态管理")).isEqualTo("状态管理");
        assertThat(RedisBm25KeywordSearch.toQueryTerm("React17-新版")).isEqualTo("React17 新版");
        assertThat(RedisBm25KeywordSearch.toQueryTerm("java.util.List")).isEqualTo("java util List");
        // 查询语法字符必须被剥离，防止被当作 RediSearch 操作符
        assertThat(RedisBm25KeywordSearch.toQueryTerm("a|b(c)")).isEqualTo("a b c");
        assertThat(RedisBm25KeywordSearch.toQueryTerm("  ")).isEmpty();
        assertThat(RedisBm25KeywordSearch.toQueryTerm(null)).isEmpty();
    }

    @Test
    void accumulatesScoreAcrossTokensAndSortsDescending() {
        when(kbConfig.getBm25IndexName()).thenReturn("kb_bm25_test");
        // 先构建返回值再打桩：thenReturn 参数里嵌套 when() 会触发 UnfinishedStubbing
        SearchResult first = searchResult(
                doc("ittest:1", 10L, 0, "文档一"),
                doc("ittest:2", 20L, 1, "文档二"));
        SearchResult second = searchResult(
                doc("ittest:2", 20L, 1, "文档二"));
        when(jedis.ftSearch(anyString(), any(Query.class))).thenReturn(first, second);

        List<KbChunkHit> hits = newSearcher().search(List.of("状态", "管理"), 5);

        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).getDocId()).isEqualTo(20L);
        assertThat(hits.get(0).getScore()).isEqualTo(2.0);
        assertThat(hits.get(1).getDocId()).isEqualTo(10L);
        assertThat(hits.get(1).getScore()).isEqualTo(1.0);
        assertThat(hits.get(0).getText()).contains("文档二");
        assertThat(hits.get(0).getSource()).isEqualTo("keyword");
    }

    @Test
    void emptyResultReturnsEmptyList() {
        when(kbConfig.getBm25IndexName()).thenReturn("kb_bm25_test");
        SearchResult empty = searchResult();
        when(jedis.ftSearch(anyString(), any(Query.class))).thenReturn(empty);

        assertThat(newSearcher().search(List.of("不存在的词"), 5)).isEmpty();
    }

    @Test
    void propagatesExceptionToCallerForFallback() {
        when(kbConfig.getBm25IndexName()).thenReturn("kb_bm25_test");
        when(jedis.ftSearch(anyString(), any(Query.class))).thenThrow(new RuntimeException("redis down"));

        assertThatThrownBy(() -> newSearcher().search(List.of("状态"), 5))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("redis down");
    }

    private Document doc(String key, long docId, int chunkIndex, String text) {
        // RETURN 别名路径下属性值为 JSON 编码字符串（数字裸值、字符串带引号）
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> props = Map.of(
                "$.docId", String.valueOf(docId),
                "$.chunkIndex", String.valueOf(chunkIndex),
                "$.title", "\"t\"",
                "$.filePath", "\"p\"",
                "$.text", "\"" + text + "\"");
        return new Document(key, props);
    }

    private SearchResult searchResult(Document... docs) {
        // SearchResult 无公开构造器，用 mock 承载文档列表
        SearchResult result = mock(SearchResult.class);
        when(result.getDocuments()).thenReturn(java.util.Arrays.asList(docs));
        return result;
    }
}
