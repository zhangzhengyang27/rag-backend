package com.techcodex.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.Comment;

import java.util.Date;

/**
 * 缺口聚类质心：在线把新问题归并到最近的已有簇，避免引入离线聚类任务。
 * 质心用加权平均增量更新；阈值内的语义相近问题共享 clusterKey。
 */
@EqualsAndHashCode(callSuper = true)
@Table(name = "kb_content_gap_cluster", indexes = {
        @Index(name = "idx_gap_cluster_library_seen", columnList = "libraryId,lastSeen")
})
@Entity
@Data
@Comment("内容缺口聚类质心")
public class KbContentGapClusterEntity extends BaseEntity {

    @Column(length = 64)
    private String libraryId;

    @Column(length = 255)
    private String label;

    @Lob
    private String centroidJson;

    @Column(nullable = false)
    private Integer size = 0;

    private Date lastSeen;
}
