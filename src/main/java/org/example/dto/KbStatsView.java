package org.example.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 知识库统计视图：文档数、分块数与索引状态分布。
 */
@Data
@Builder
public class KbStatsView {

    private long totalDocuments;

    private long totalChunks;

    private long indexedDocuments;

    /** 待索引（PENDING）文档数。 */
    private long pendingDocuments;

    /** 索引失败（FAILED）文档数。 */
    private long failedDocuments;

    private long totalVectorCount;
}
