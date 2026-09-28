package org.example.vo;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * 单条审计日志视图（管理端审计日志页用）。
 */
@Data
@Builder
public class AuditLogView {

    private Long id;

    private String libraryId;

    private String operator;

    private String action;

    private String targetType;

    private Long targetId;

    private String detail;

    private Date createdDate;
}
