package org.example.service;

import org.example.vo.ChatUsageView;
import org.example.vo.UsageSummaryView;
import org.example.vo.UserUsageView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * 聊天用量统计服务：记录每次问答用量（token 估算/耗时/命中数），提供汇总与明细查询。
 */
public interface UsageStatService {

    /**
     * 记录一次问答用量（旁路写入，失败仅记日志不影响主链路）。
     *
     * @param libraryId       空间
     * @param userId         用户
     * @param conversationId 对话ID
     * @param question       用户问题
     * @param answer         助手回答（用于估算输出 token）
     * @param inputTokens    输入 token 估算（问题+历史+证据上下文）
     * @param outputTokens   输出 token 估算（回答长度）
     * @param elapsedMs      问答耗时（毫秒）
     * @param hitCount       检索命中片段数
     */
    void record(String libraryId, String userId, String conversationId, String question, String answer,
                int inputTokens, int outputTokens, long elapsedMs, int hitCount);

    /** 近 N 天用量汇总 + 按日趋势（按空间隔离）。 */
    UsageSummaryView summary(String libraryId, int days);

    /** 用量明细分页（按空间隔离，时间倒序）。 */
    Page<ChatUsageView> recent(String libraryId, Pageable page);

    /**
     * 用户维度用量 Top 排行（按问答数降序），供管理端识别重度用户。
     *
     * @param libraryId 空间
     * @param days     统计周期（天）
     * @param limit    最多返回的用户数
     */
    List<UserUsageView> topUsers(String libraryId, int days, int limit);
}
