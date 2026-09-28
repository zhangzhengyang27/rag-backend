package org.example.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.Comment;

/**
 * RAG 评测趋势报告：每晚定时跑一次全量评测并落库，供管理页查看历史趋势与回归告警。
 * 报告按空间隔离：同一日期每个空间各一条。
 */
@EqualsAndHashCode(callSuper = true)
@Table(name = "kb_rag_eval_report",
        uniqueConstraints = @UniqueConstraint(name = "uk_eval_report_date_library",
                columnNames = {"eval_date", "library_id"}))
@Entity
@Data
@Comment("RAG 评测趋势报告（每空间每日一条）")
public class KbEvalReportEntity extends BaseEntity {

    @Comment("评测日期（yyyy-MM-dd，每空间每天一条）")
    @Column(name = "eval_date", length = 16, nullable = false)
    private String date;

    @Comment("所属空间 code")
    @Column(length = 64)
    private String libraryId;

    @Comment("评测用例数")
    @Column(nullable = false)
    private Integer caseCount;

    @Comment("命中率")
    @Column(nullable = false)
    private Double hitRate;

    @Comment("MRR")
    @Column(nullable = false)
    private Double mrr;

    @Comment("NDCG")
    @Column(nullable = false)
    private Double ndcg;

    @Comment("评测快照（JSON：未命中用例的 question/missingKeywords/topTitles）")
    @Column(columnDefinition = "TEXT")
    private String snapshot;
}
