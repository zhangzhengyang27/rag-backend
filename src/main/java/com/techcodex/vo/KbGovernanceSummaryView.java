package com.techcodex.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 知识治理健康度汇总：重复、过期、缺责任人、缺分块四类问题与整体健康分。
 */
@Data
@Builder
public class KbGovernanceSummaryView {

    private long totalDocuments;

    /** 0~100；每篇文档先算分，再取平均 */
    private double avgHealthScore;

    private long duplicateDocuments;

    private long staleDocuments;

    private long missingOwnerDocuments;

    private long emptyChunkDocuments;

    /** 重复标题分组（最多 20 组）：[规范化标题, 文档数, 示例路径] */
    private List<DuplicateGroup> duplicateGroups;

    @Data
    @Builder
    public static class DuplicateGroup {
        private String normalizedTitle;
        private long count;
        private String samplePath;
    }
}
