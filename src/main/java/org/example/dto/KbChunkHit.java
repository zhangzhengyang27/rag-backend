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

    /** 标题路径（如 Java > 集合 > HashMap；首个标题前为空） */
    private String headingPath;

    /** 当前分块所属标题文本（前端用 extractToc 映射锚点；无标题为 null） */
    private String heading;

    /** 预留锚点字段：后端不生成 slug，由前端按 heading 映射；当前与 heading 同值便于旧前端兜底 */
    private String anchor;

    /** 标题序号（0=首个标题前，标题按文档顺序 1-based 递增） */
    private Integer headingOrdinal;

    /** 分块类型：text=正文，code=代码块 */
    private String chunkType;

    /** 代码语言（chunkType=code 时记录围栏语言，如 java/bash） */
    private String codeLang;

    /** 引用编号（从 1 开始，与回答中的 [n] 一一对应；仅问答 sources 事件填充） */
    private String citationId;
}
