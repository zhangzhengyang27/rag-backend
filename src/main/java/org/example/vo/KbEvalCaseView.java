package org.example.vo;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * 管理端评测用例视图（Golden Set 维护）。
 */
@Data
@Builder
public class KbEvalCaseView {

    private Long id;

    private String question;

    private String expectedKeywords;

    private String expectedDocIds;

    private String note;

    private String createdBy;

    private Date createdDate;
}
