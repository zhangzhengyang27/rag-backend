package com.techcodex.service;

import java.util.Map;

/** GraphRAG 门禁前置的文档级实体/关系标注。 */
public interface KbDeepAnnotationService {

    /**
     * 从文档标题、章节与 Markdown 出链生成文档级实体/关系样本。
     * 只统计链接目标能解析到知识库现有文档的关系；不生成开放域 NER 假数据。
     */
    /**
     * 从文档标题、章节与 Markdown 出链生成文档级实体/关系样本。
     * 只统计链接目标能解析到知识库现有文档的关系；不生成开放域 NER 假数据。
     *
     * @param libraryId 目标知识库空间，由调用方显式传入。
     *                  不能在实现内写死：个人空间与自定义空间同样需要标注能力，
     *                  写死会导致这些空间调用后拿到空结果且不报错。
     */
    Map<String, Object> seedLinkGraph(int limit, String libraryId);

    /** 当前标注样本汇总（文档数/实体数/关系数/状态分布），按空间过滤。 */
    Map<String, Object> summary(String libraryId);
}
