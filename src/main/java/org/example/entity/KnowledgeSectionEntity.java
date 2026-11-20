package org.example.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.Comment;

/**
 * 章节父块：同一文档同一标题序号下的子块聚合文本。
 * 不单独建向量，只用于检索命中后的章节上下文扩展。
 */
@EqualsAndHashCode(callSuper = true)
@Table(name = "kb_section", indexes = {
        @Index(name = "idx_kb_section_library", columnList = "libraryId"),
        @Index(name = "idx_kb_section_doc", columnList = "docId")
})
@Entity
@Data
@Comment("知识库章节父块")
public class KnowledgeSectionEntity extends BaseEntity {

    @Column(length = 64)
    private String libraryId;

    @Column(nullable = false)
    private Long docId;

    @Column(nullable = false)
    private Integer headingOrdinal;

    @Column(length = 512)
    private String headingPath;

    @Column(length = 1024)
    private String anchor;

    @Column(columnDefinition = "LONGTEXT")
    private String text;

    private Integer chunkCount;

    private Integer tokenCount;
}
