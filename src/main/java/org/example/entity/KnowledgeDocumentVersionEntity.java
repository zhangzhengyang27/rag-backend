package org.example.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.Comment;

/**
 * CMS 文档版本快照：每次发布追加一条，正文为完整 Markdown（含 frontmatter）。
 * 与 kb_document 分离，避免正文 LONGTEXT 版本把主表撑大；回滚时读取快照重新发布。
 */
@EqualsAndHashCode(callSuper = true)
@Table(name = "kb_document_version", indexes = {
        @Index(name = "idx_kb_version_doc_revision", columnList = "docId,revision"),
        @Index(name = "idx_kb_version_created", columnList = "createdDate")
})
@Entity
@Data
@Comment("CMS 文档版本快照")
public class KnowledgeDocumentVersionEntity extends BaseEntity {

    @Comment("文档 id")
    @Column(nullable = false)
    private Long docId;

    @Comment("内容版本号（与 kb_document.revision 对齐）")
    @Column(nullable = false)
    private Integer revision;

    @Comment("快照标题")
    @Column(length = 500)
    private String title;

    @Comment("快照摘要")
    @Column(length = 2000)
    private String description;

    @Comment("快照 Markdown 原文（含 frontmatter）")
    @Column(columnDefinition = "LONGTEXT")
    private String content;

    @Comment("快照内容哈希")
    @Column(length = 64)
    private String contentHash;

    @Comment("变更说明")
    @Column(length = 500)
    private String changeNote;

    @Comment("发布人")
    @Column(length = 64)
    private String createdBy;
}
