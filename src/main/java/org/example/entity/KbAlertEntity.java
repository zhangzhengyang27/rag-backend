package org.example.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.Comment;

/**
 * 运营告警：评测质量低于阈值、docs 同步存在失败条目等需要管理员关注的事件。
 * 同类未处理告警去重（不重复堆积），处理后置 resolved 留痕。
 */
@EqualsAndHashCode(callSuper = true)
@Table(name = "kb_alert",
        uniqueConstraints = @UniqueConstraint(name = "uk_kb_alert_dedupe", columnNames = "dedupeKey"),
        indexes = {
        @Index(name = "idx_kb_alert_resolved", columnList = "libraryId, resolved")
})
@Entity
@Data
@Comment("运营告警")
public class KbAlertEntity extends BaseEntity {

    @Comment("空间业务键（default=公共知识库）")
    @Column(length = 64)
    private String libraryId;

    @Comment("告警类型：EVAL_LOW_HITRATE / DOCS_SYNC_FAILED 等")
    @Column(length = 64)
    private String type;

    /** 严重级别：WARN / CRITICAL */
    @Column(length = 20)
    private String severity;

    @Comment("告警标题")
    @Column(length = 255)
    private String message;

    @Comment("告警详情（命中情况/失败明细等）")
    @Lob
    private String detail;

    @Comment("是否已处理")
    private boolean resolved;

    /**
     * 去重键：未处理时等于告警类型（唯一约束，跨实例杜绝同类未处理告警并发重复），
     * 处理后置 NULL（MySQL 唯一索引允许多个 NULL，不限制历史告警）。仅新建 schema 自动建约束。
     */
    @Comment("去重键：未处理时=type（唯一），处理后置 NULL")
    @Column(length = 64)
    private String dedupeKey;

    @Comment("处理人")
    @Column(length = 64)
    private String resolvedBy;
}
