package org.example.service;

import org.example.entity.KnowledgeDocumentEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文档信息本地缓存（标题/路径/space 等检索溯源字段）。
 *
 * <p>混合检索的关键词路会按 docId 回查文档信息；缓存避免高频重复主键查询。
 * 有界 LRU（默认 500 条）防止无上限增长；文档更新/删除时必须调用 {@link #evict}
 * 失效对应条目，否则检索溯源会返回旧标题/旧路径。</p>
 */
@Component
public class DocumentInfoCache {

    private final Map<Long, KnowledgeDocumentEntity> cache;

    public DocumentInfoCache(@Value("${app.kb.doc-cache.max-entries:500}") int maxEntries) {
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, KnowledgeDocumentEntity> eldest) {
                return size() > Math.max(1, maxEntries);
            }
        });
    }

    public KnowledgeDocumentEntity get(Long docId) {
        return cache.get(docId);
    }

    public void put(Long docId, KnowledgeDocumentEntity doc) {
        if (docId != null && doc != null) {
            cache.put(docId, doc);
        }
    }

    /** 文档更新/删除后调用：立即失效对应条目，避免溯源读到旧数据。 */
    public void evict(Long docId) {
        if (docId != null) {
            cache.remove(docId);
        }
    }
}
