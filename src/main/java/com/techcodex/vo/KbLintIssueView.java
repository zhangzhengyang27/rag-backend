package com.techcodex.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 内容 Lint 单条问题：断链或同名标题，均带可复制的修复建议。
 */
@Data
@Builder
public class KbLintIssueView {

    public static final String BROKEN_LINK = "BROKEN_LINK";
    public static final String DUPLICATE_TITLE = "DUPLICATE_TITLE";

    private Long docId;
    private String type;
    private String title;
    private String filePath;
    private String detail;
    private String suggestion;
    /** 文档责任人/团队队列；空表示尚未分派 */
    private String owner;
}
