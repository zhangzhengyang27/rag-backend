package org.example.vo;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

/** 缺口列表/详情共用的展示模型；signalSummary 是 "TYPE:count" 的逗号串。 */
@Data
@Builder
public class ContentGapView {
    private Long id;
    private String question;
    private String normalized;
    private Integer frequency;
    private String status;
    private String signalSummary;
    private String clusterKey;
    private String clusterLabel;
    private String relatedDocIds;
    private String sampleConversationId;
    private Long evalCaseId;
    private String note;
    private Date firstSeen;
    private Date lastSeen;
    private Date createdDate;
}
