package org.example.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.Comment;

/**
 * 缺口信号事件：每次未答/零引用/点踩/追问都保留一条，供详情追溯。
 * 与聚合表分离，避免单个缺口高频更新时把历史信号淹没。
 */
@EqualsAndHashCode(callSuper = true)
@Table(name = "kb_content_gap_event", indexes = {
        @Index(name = "idx_gap_event_gap", columnList = "gapId"),
        @Index(name = "idx_gap_event_library_time", columnList = "libraryId,createdDate")
})
@Entity
@Data
@Comment("内容缺口信号事件")
public class KbContentGapEventEntity extends BaseEntity {

    private Long gapId;

    @Column(length = 64)
    private String libraryId;

    @Column(length = 32)
    private String signalType;

    @Column(length = 64)
    private String conversationId;

    private Long feedbackId;

    @Column(length = 1000)
    private String detail;
}
