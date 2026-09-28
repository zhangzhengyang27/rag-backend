package org.example.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.Comment;

/**
 * RAG 评测用例（Golden Set）：一条"问题 + 期望命中关键字"，用于离线评测检索召回质量。
 * 命中判定：检索 topN 结果中任一片段正文包含全部期望关键字即视为命中。
 */
@EqualsAndHashCode(callSuper = true)
@Table(name = "kb_rag_eval")
@Entity
@Data
@Comment("RAG 评测用例")
public class RagEvalCaseEntity extends BaseEntity {

    @Comment("评测问题")
    @Column(columnDefinition = "TEXT")
    private String question;

    @Comment("期望命中的关键字（逗号分隔，全部命中才算召回成功）")
    @Column(columnDefinition = "TEXT")
    private String expectedKeywords;

    @Comment("期望命中的文档 id（逗号分隔，用于 NDCG 排序质量评估；可为空则跳过该用例的 NDCG 计算）")
    @Column(columnDefinition = "TEXT")
    private String expectedDocIds;

    @Comment("备注/预期答案说明")
    @Column(columnDefinition = "TEXT")
    private String note;

    @Comment("创建人")
    @Column(length = 64)
    private String createdBy;
}
