package org.example.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.Comment;

/**
 * 知识库文档分块表：文档经 Markdown 解析后按段落切分，每块一条记录。
 * 记录分块顺序与正文；向量化后的向量存储于 Redis 专用索引 {@link org.example.config.KbConfig} 中。
 */
@EqualsAndHashCode(callSuper = true)
@Table(name = "kb_chunk", indexes = {
        @Index(name = "idx_kb_chunk_doc", columnList = "docId"),
        @Index(name = "idx_kb_chunk_library_doc", columnList = "libraryId, docId"),
        @Index(name = "idx_kb_chunk_doc_ordinal", columnList = "docId, headingOrdinal")
})
@Entity
@Data
@Comment("知识库文档分块")
public class KnowledgeChunkEntity extends BaseEntity {

    @Comment("空间业务键（组织隔离维度；存量数据回填 default）")
    @Column(length = 64)
    private String libraryId;

    @Comment("所属文档ID")
    @Column(nullable = false)
    private Long docId;

    @Comment("分块序号")
    @Column(nullable = false)
    private Integer chunkIndex;

    @Comment("分块正文")
    @Column(columnDefinition = "LONGTEXT")
    private String text;

    @Comment("字符数")
    private Integer charCount;

    @Comment("标题路径（如 Java > 集合 > HashMap；首个标题前为空）")
    @Column(length = 512)
    private String headingPath;

    @Comment("标题文本（前端用 extractToc 映射锚点；无标题为空）")
    @Column(length = 255)
    private String anchor;

    @Comment("标题序号（0=首个标题前，标题按文档顺序 1-based 递增）")
    @Column(nullable = false)
    private Integer headingOrdinal = 0;

    @Comment("分块类型：text=正文，code=代码块")
    @Column(length = 16, nullable = false)
    private String chunkType = "text";

    @Comment("代码语言（chunkType=code 时记录围栏语言，如 java/bash）")
    @Column(length = 32)
    private String codeLang;

    @Comment("父分块ID（父子分块；Phase 3 启用，当前为空）")
    private Long parentId;

    @Comment("估算 token 数（用于语义缓存与上下文预算）")
    @Column(nullable = false)
    private Integer tokenCount = 0;

    @Comment("Redis 向量存储的 ID（addAll 返回），用于按 ID 精准删除旧向量")
    @Column(length = 64)
    private String vectorId;
}
