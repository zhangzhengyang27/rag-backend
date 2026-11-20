package org.example.vo;

import lombok.Builder;
import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 全站内容 Lint 报告：断链/同名标题计数 + 问题清单 + 可直接贴进 PR 的 Markdown 建议。
 */
@Data
@Builder
public class KbLintReportView {

    private String libraryId;
    private int docCount;
    private int linkCount;
    private int brokenLinkCount;
    private int duplicateTitleCount;
    private List<KbLintIssueView> issues;
    private String prSummary;
    private Date scannedAt;
}
