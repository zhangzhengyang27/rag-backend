package org.example.vo;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * 运营告警视图。
 */
@Data
@Builder
public class KbAlertView {

    private Long id;

    private String libraryId;

    private String type;

    private String severity;

    private String message;

    private String detail;

    private boolean resolved;

    private String resolvedBy;

    private Date createdDate;
}
