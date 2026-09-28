package org.example.vo;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * 用户维度用量视图：Top 用户排行（按问答数降序）。
 */
@Data
@Builder
public class UserUsageView {

    private String userId;

    /** 周期内问答数 */
    private long questions;

    /** 周期内 token 估算总量 */
    private long totalTokens;

    /** 平均问答耗时（毫秒） */
    private double avgLatencyMs;

    /** 平均命中片段数 */
    private double avgHitCount;

    /** 最近一次活跃时间 */
    private Date lastActiveAt;
}
