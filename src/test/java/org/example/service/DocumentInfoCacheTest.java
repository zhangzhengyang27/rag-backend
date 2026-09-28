package org.example.service;

import org.example.entity.KnowledgeDocumentEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文档信息缓存单测：有界淘汰 + 显式失效。
 */
class DocumentInfoCacheTest {

    private KnowledgeDocumentEntity doc(long id) {
        KnowledgeDocumentEntity d = new KnowledgeDocumentEntity();
        d.setId(id);
        d.setTitle("doc" + id);
        return d;
    }

    @Test
    void evictsExplicitly() {
        DocumentInfoCache cache = new DocumentInfoCache(10);
        cache.put(1L, doc(1L));
        assertThat(cache.get(1L)).isNotNull();

        cache.evict(1L);

        assertThat(cache.get(1L)).isNull();
    }

    @Test
    void boundedSizeEvictsEldest() {
        DocumentInfoCache cache = new DocumentInfoCache(3);
        cache.put(1L, doc(1L));
        cache.put(2L, doc(2L));
        cache.put(3L, doc(3L));
        // 访问 1 使其变为最新，插入 4 时应淘汰 2
        cache.get(1L);
        cache.put(4L, doc(4L));

        assertThat(cache.get(1L)).isNotNull();
        assertThat(cache.get(2L)).isNull();
        assertThat(cache.get(3L)).isNotNull();
        assertThat(cache.get(4L)).isNotNull();
    }
}
