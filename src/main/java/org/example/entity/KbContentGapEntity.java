package org.example.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.Comment;

import java.util.Date;

/**
 * 内容缺口聚合：同一归一化问题只保留一行，频次随信号累加。
 * 这里只存问题/信号快照，不存回答正文，避免把用户对话内容扩散到运营表。
 */
@EqualsAndHashCode(callSuper = true)
@Table(name = "kb_content_gap", indexes = {
        @Index(name = "idx_gap_library_status_freq", columnList = "libraryId,status,frequency"),
        @Index(name = "idx_gap_library_last_seen", columnList = "libraryId,lastSeen")
})
@Entity
@Data
@Comment("内容缺口聚合")
public class KbContentGapEntity extends BaseEntity {

    @Column(length = 64)
    private String libraryId;

    @Column(length = 2000)
    private String question;

    @Column(length = 512)
    private String normalized;

    @Column(length = 64)
    private String normalizedHash;

    /** OPEN / TRIAGED / PLANNED / RESOLVED / IGNORED */
    @Column(length = 16, nullable = false)
    private String status = "OPEN";

    @Column(nullable = false)
    private Integer frequency = 1;

    private Date firstSeen;

    private Date lastSeen;

    @Column(length = 1000)
    private String relatedDocIds;

    @Column(length = 255)
    private String signalSummary;

    @Column(length = 64)
    private String sampleConversationId;

    @Column(length = 64)
    private String sampleUserId;

    @Column(length = 64)
    private String clusterKey;

    private Long evalCaseId;

    @Column(length = 1000)
    private String note;
}
