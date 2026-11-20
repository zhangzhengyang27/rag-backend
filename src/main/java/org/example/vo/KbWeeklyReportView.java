package org.example.vo;

import lombok.Builder;
import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 运营周报视图：把用量/反馈/评测/告警四类观测数据聚合成一份管理端周报，
 * 核心产出是「点踩 Top 问题榜」与「内容缺口 Top 10」——共同作为下一轮内容补强的工作清单。
 */
@Data
@Builder
public class KbWeeklyReportView {

    /** 统计周期（天） */
    private int days;

    /** 报告生成时间 */
    private Date generatedAt;

    /** 用量汇总 + 按日趋势（复用用量统计服务） */
    private UsageSummaryView usage;

    /** 周期内点赞数 */
    private long feedbackUp;

    /** 周期内点踩数 */
    private long feedbackDown;

    /** 点踩率：down / (up + down)，无反馈时为 0 */
    private double downRate;

    /** 当前打开中的运营告警数 */
    private long openAlerts;

    /** 点踩 Top 问题榜（按出现次数降序，最多 10 条）——内容补强清单 */
    private List<DownQuestionView> downTopQuestions;

    /** 内容缺口 Top 10（未解决/未忽略，按频次降序）——周报必带的内容生产清单 */
    private List<org.example.vo.ContentGapView> gapTop;

    /** 知识治理健康度：平均分与四类问题的数量，周报里和内容缺口并列。 */
    private org.example.vo.KbGovernanceSummaryView governance;

    /** 检索质量趋势（复用评测日报） */
    private List<KbEvalReportView> evalTrend;
}
