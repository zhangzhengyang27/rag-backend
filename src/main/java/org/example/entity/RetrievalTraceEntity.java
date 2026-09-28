package org.example.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.Comment;

/**
 * 知识库检索 Trace：记录每次混合检索的召回明细，用于观测与调优（对应大厂 RAG 可观测性）。
 * 轻量异步写入，不影响检索主链路。
 */
@EqualsAndHashCode(callSuper = true)
@Table(name = "kb_retrieval_trace")
@Entity
@Data
@Comment("知识库检索追踪")
public class RetrievalTraceEntity extends BaseEntity {

    @Comment("空间业务键（组织隔离维度；存量数据回填 default）")
    @Column(length = 64)
    private String libraryId;

    @Comment("用户ID")
    @Column(length = 64)
    private String userId;

    @Comment("原始查询")
    @Column(columnDefinition = "TEXT")
    private String originalQuery;

    @Comment("检索用查询（可能经改写）")
    @Column(columnDefinition = "TEXT")
    private String searchQuery;

    @Comment("是否命中关键词通道")
    private Boolean keywordUsed;

    @Comment("向量召回数")
    private Integer vectorCount;

    @Comment("关键词召回数")
    private Integer keywordCount;

    @Comment("RRF 融合后候选数")
    private Integer fusedCount;

    @Comment("是否执行语义重排")
    private Boolean reranked;

    @Comment("重排模型是否可用")
    private Boolean rerankModelAvailable;

    @Comment("最终返回给 LLM 的片段数")
    private Integer resultCount;

    @Comment("检索耗时（毫秒）")
    private Long elapsedMs;

    @Comment("命中片段摘要（docId#chunkIndex，逗号分隔）")
    @Column(columnDefinition = "TEXT")
    private String hitSummary;

    @Comment("目录过滤前缀")
    @Column(length = 255)
    private String pathFilter;
}
