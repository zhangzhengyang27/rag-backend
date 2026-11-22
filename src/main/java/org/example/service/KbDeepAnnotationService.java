package org.example.service;

import java.util.Map;

/** GraphRAG 门禁前置的文档级实体/关系标注。 */
public interface KbDeepAnnotationService {

    /**
     * 从文档标题、章节与 Markdown 出链生成文档级实体/关系样本。
     * 只统计链接目标能解析到知识库现有文档的关系；不生成开放域 NER 假数据。
     */
    Map<String, Object> seedLinkGraph(int limit);

    /** 当前标注样本汇总（文档数/实体数/关系数/状态分布）。 */
    Map<String, Object> summary();
}
