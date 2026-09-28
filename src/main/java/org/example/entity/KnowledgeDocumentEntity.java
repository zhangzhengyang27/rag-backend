package org.example.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.Comment;

/**
 * 知识库文档元数据表：记录每份 Markdown 文档的来源、标题、内容与索引状态。
 * 向量由 Redis 存储，正文存于 {@link KnowledgeChunkEntity}，本表维护文档级元数据与溯源信息。
 * 索引：空间维度查询（列表/统计/检索 docId 集）与空间内去重（内容哈希）走组合索引；
 * filePath 前缀查询不建索引（utf8mb4 下 1024 长度超限，目录导入低频可接受）。
 */
@EqualsAndHashCode(callSuper = true)
@Table(name = "kb_document", indexes = {
        @Index(name = "idx_kb_doc_library", columnList = "libraryId"),
        @Index(name = "idx_kb_doc_library_space", columnList = "libraryId, space"),
        @Index(name = "idx_kb_doc_library_hash", columnList = "libraryId, contentHash")
})
@Entity
@Data
@Comment("知识库文档元数据")
public class KnowledgeDocumentEntity extends BaseEntity {

    @Comment("文档标题（默认取文件名）")
    @Column(length = 500)
    private String title;

    @Comment("原始文件名")
    @Column(length = 500)
    private String fileName;

    @Comment("源文件相对路径（溯源与目录导入用）")
    @Column(length = 1024)
    private String filePath;

    @Comment("文件大小（字节）")
    private Long fileSize;

    @Comment("正文内容哈希（用于去重，避免重复导入）")
    @Column(length = 64)
    private String contentHash;

    @Comment("Markdown 原文（预览用；分块内容在 kb_chunk）")
    @Column(columnDefinition = "LONGTEXT")
    private String content;

    @Comment("分块数量（已成功向量化的片段数）")
    private Integer chunkCount = 0;

    @Comment("导入用户")
    @Column(length = 64)
    private String importedBy;

    @Comment("来源类型：UPLOAD=单文件上传，URL=网页导入，DIRECTORY=目录导入，SYNC=docs 目录自动同步（受管，随目录对账增删改）")
    @Column(length = 20)
    private String sourceType;

    @Comment("空间业务键（组织隔离维度；存量数据回填 default）")
    @Column(length = 64)
    private String libraryId;

    @Comment("知识库空间/分类（默认取 filePath 顶级目录，如 前端/后端/架构与工程；也可手动指定，用于空间隔离检索）")
    @Column(length = 128)
    private String space;

    @Comment("站点 slug（from 文档 frontmatter.slug，相对 /docs/ 的跳转标识；无则空）")
    @Column(length = 512)
    private String siteSlug;

    @Comment("frontmatter category（课程级标签，如 JavaScript/Nginx；写入向量 metadata 供分类范围检索）")
    @Column(length = 128)
    private String category;

    @Comment("索引状态：PENDING=待索引，INDEXED=已索引，FAILED=失败")
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private KbDocumentStatus status = KbDocumentStatus.PENDING;
}
