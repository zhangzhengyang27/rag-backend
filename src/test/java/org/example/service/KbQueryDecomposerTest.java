package org.example.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 查询分解是确定性的，必须锁住“切什么、不切什么、最多几条”这三个边界。 */
class KbQueryDecomposerTest {

    @Test
    void splitsTwoQuestionIntentsAndKeepsOriginalFirst() {
        List<String> parts = KbQueryDecomposer.decompose(
                "Webpack 和 Vite 的构建流程有什么区别？为什么 Vite 启动更快？");
        assertEquals(3, parts.size());
        assertEquals("Webpack 和 Vite 的构建流程有什么区别？为什么 Vite 启动更快？", parts.get(0));
        assertTrue(parts.contains("Webpack 和 Vite 的构建流程有什么区别"));
        assertTrue(parts.contains("为什么 Vite 启动更快"));
    }

    @Test
    void splitsChineseAndButDoesNotSplitInsideCacheControl() {
        List<String> parts = KbQueryDecomposer.decompose(
                "HTTP 的 Cache-Control 和 ETag 如何配合强缓存与协商缓存？");
        assertEquals(3, parts.size());
        assertTrue(parts.stream().noneMatch(p -> p.contains("Cache-") && !p.contains("Cache-Control")));
        assertTrue(parts.contains("HTTP 的 Cache-Control"));
    }

    @Test
    void doesNotSplitTechnicalSlashOrHyphenOnSingleIntentQuery() {
        List<String> parts = KbQueryDecomposer.decompose(
                "async/await 的微任务队列如何协作？");
        assertEquals(1, parts.size());
        assertEquals("async/await 的微任务队列如何协作？", parts.get(0));
    }

    @Test
    void dropsTooShortSubQueryAndHandlesBlank() {
        assertEquals(List.of(), KbQueryDecomposer.decompose("   "));
        List<String> parts = KbQueryDecomposer.decompose("MVCC 与事务");
        assertEquals(1, parts.size(), "“事务”只有 2 个字，不能作为独立子查询");
    }

    @Test
    void capsAtOriginalPlusTwoSubQueries() {
        List<String> parts = KbQueryDecomposer.decompose(
                "Spring Boot 是什么？Vue 3 是什么？React 是什么？Node.js 是什么？");
        assertEquals(3, parts.size());
    }
}
