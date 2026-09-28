package org.example.vo;

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

    private List<UsageTrendPoint> trend;
}
