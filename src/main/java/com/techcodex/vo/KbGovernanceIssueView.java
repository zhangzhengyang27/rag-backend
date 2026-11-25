package com.techcodex.vo;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * 单条治理问题：同一文档可同时有多类问题，前端按类型筛选。
 */
@Data
@Builder
public class KbGovernanceIssueView {

    public static final String DUPLICATE = "DUPLICATE";
    public static final String STALE = "STALE";
    public static final String MISSING_OWNER = "MISSING_OWNER";
    public static final String EMPTY_CHUNKS = "EMPTY_CHUNKS";

    private Long docId;
    private String type;
    private String severity;
    private String title;
    private String filePath;
    private String detail;
    private String owner;
    private Date reviewDueDate;
    private Date updatedDate;
    private int healthScore;
}
