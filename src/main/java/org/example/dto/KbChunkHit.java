package org.example.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 知识库检索命中片段：附带文档溯源信息（标题/路径/分块序号）供回答引用。
 */
@Data
@Builder
public class KbChunkHit {

    private Long docId;

    private Integer chunkIndex;

    private String title;

    private String filePath;

    private String text;

    private Double score;

    /** 召回来源路：vector=向量召回，keyword=关键词召回（供溯源与调试区分路置信度；RRF 融合保留原路标识） */
    private String source;

    /** 站点跳转 slug（相对 /docs/，来自文档 frontmatter.slug；缺失为 null） */
    private String siteSlug;
}
