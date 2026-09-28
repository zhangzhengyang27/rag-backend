package org.example.config;

import lombok.extern.slf4j.Slf4j;
import org.example.search.RedisBm25KeywordSearch;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * BM25 关键词补充索引启动初始化：仅在 keyword-backend=redis 时确保索引存在
 * （幂等，已存在则跳过）。创建失败不阻断启动——关键词检索会回退 MySQL LIKE。
 * 注意：对存量数据首次创建索引时，RediSearch 会在后台异步建索引，完成前的
 * 查询可能只有部分结果（同样走回退兜底）。
 */
@Slf4j
@Component
public class Bm25KeywordIndexInitializer implements ApplicationRunner {

    private final RedisBm25KeywordSearch redisBm25KeywordSearch;

    @Value("${app.kb.retrieval.keyword-backend:mysql}")
    private String keywordBackend;

    public Bm25KeywordIndexInitializer(RedisBm25KeywordSearch redisBm25KeywordSearch) {
        this.redisBm25KeywordSearch = redisBm25KeywordSearch;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!"redis".equalsIgnoreCase(keywordBackend)) {
            return;
        }
        try {
            redisBm25KeywordSearch.ensureConfiguredIndex();
        } catch (Exception e) {
            log.warn("BM25 关键词索引初始化失败，关键词检索将回退 MySQL LIKE - err: {}", e.getMessage());
        }
    }
}
