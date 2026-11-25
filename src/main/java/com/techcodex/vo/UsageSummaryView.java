package com.techcodex.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 用量统计汇总视图：周期内的总量指标 + 按日趋势（供管理端看板渲染）。
 */
@Data
@Builder
public class UsageSummaryView {

    private long totalQuestions;

    private long totalInputTokens;

    private long totalOutputTokens;

    private long totalTokens;

    /** 平均耗时（毫秒，保留一位小数） */
    private double avgLatencyMs;

    /** 平均命中片段数（保留一位小数） */
    private double avgHitCount;

    /** 未答数：回答为空（相关度拒答/知识库无内容） */
    private long unansweredQuestions;

    /** 未答率：unansweredQuestions / totalQuestions；P1 门禁要求 < 10% */
    private double unansweredRate;

    /** 命中语义缓存的问答数 */
    private long cacheHitCount;

    /** 估算节省的输出 token 总量 */
    private long cacheSavedTokens;

    /**
     * 缓存命中率：cacheHitCount / totalQuestions。
     * 以全部请求为分母（包含本就不适合缓存的一次性问题），与 Stage 1 门禁口径一致。
     */
    private double cacheHitRate;

    private List<UsageTrendPoint> trend;
}
