package org.example.service;

import org.example.vo.KbWeeklyReportView;

/**
 * 运营周报服务：聚合用量/反馈/评测/告警四类观测数据，
 * 输出管理端周报视图（核心是点踩 Top 问题榜 → 内容补强清单）。
 */
public interface KbWeeklyReportService {

    /**
     * 生成近 N 天的运营周报（限定空间，管理端固定公共知识库）。
     *
     * @param libraryId 空间（default）
     * @param days      统计周期（1~90，越界收敛到边界）
     */
    KbWeeklyReportView weekly(String libraryId, int days);
}
