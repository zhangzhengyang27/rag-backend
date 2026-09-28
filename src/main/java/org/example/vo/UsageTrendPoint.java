package org.example.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 用量日趋势点：某一天的问答量与 token/耗时/命中聚合。
 */
@Data
@Builder
public class UsageTrendPoint {

    /** 日期（yyyy-MM-dd） */
    private String date;

    private long questions;

    private long tokens;

    /** 当日平均耗时（毫秒） */
    private double avgLatencyMs;

    /** 当日平均命中片段数 */
    private double avgHitCount;
}
