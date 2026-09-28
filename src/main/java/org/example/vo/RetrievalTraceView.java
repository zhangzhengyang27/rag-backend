package org.example.vo;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * 管理端检索 Trace 视图：单次混合检索的召回明细（可观测/调优）。
 */
@Data
@Builder
public class RetrievalTraceView {

    private Long id;

    private String libraryId;

    private String userId;

    private String originalQuery;

    private String searchQuery;

    private Boolean keywordUsed;

    private Integer vectorCount;

    private Integer keywordCount;

    private Integer fusedCount;

    private Boolean reranked;

    private Boolean rerankModelAvailable;

    private Integer resultCount;

    private Long elapsedMs;

    /** 命中片段摘要（docId#chunkIndex，逗号分隔） */
    private String hitSummary;

    private String pathFilter;

    private Date createdDate;
}
