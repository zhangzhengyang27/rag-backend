package com.techcodex.service.impl;

import lombok.extern.slf4j.Slf4j;
import com.techcodex.repository.KbChatFeedbackRepository;
import com.techcodex.service.ContentGapService;
import com.techcodex.service.KbAlertService;
import com.techcodex.service.KbEvalService;
import com.techcodex.service.KbWeeklyReportService;
import com.techcodex.service.UsageStatService;
import com.techcodex.service.KbGovernanceService;
import com.techcodex.vo.ContentGapView;
import com.techcodex.vo.DownQuestionView;
import com.techcodex.vo.KbEvalReportView;
import com.techcodex.vo.KbWeeklyReportView;
import com.techcodex.vo.UsageSummaryView;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 运营周报实现：全部指标为实时聚合（不走缓存），规模为个人/小团队站点量级，
 * 一次调用 = 用量汇总 + 2 次反馈计数 + 1 次点踩聚合 + 内容缺口 Top10 + 评测趋势 + 告警计数，开销可忽略。
 *
 * <p>管理端周报面向运营全局：用量与反馈跨空间汇总（否则普通用户个人空间的数据会全部漏掉）；
 * 缺口、评测和告警仍落在公共库，因为它们只对公共内容治理有意义。</p>
 */
@Slf4j
@Service
public class KbWeeklyReportServiceImpl implements KbWeeklyReportService {

    /** 点踩榜/缺口榜长度上限：周报聚焦最高频问题，长尾走对应明细页。 */
    private static final int DOWN_TOP_LIMIT = 10;
    private static final int GAP_TOP_LIMIT = 10;

    private static final String RATING_UP = "UP";
    private static final String RATING_DOWN = "DOWN";

    private final UsageStatService usageStatService;
    private final KbEvalService kbEvalService;
    private final KbAlertService kbAlertService;
    private final KbChatFeedbackRepository feedbackRepository;
    private final ContentGapService contentGapService;
    private final KbGovernanceService kbGovernanceService;

    public KbWeeklyReportServiceImpl(UsageStatService usageStatService,
                                     KbEvalService kbEvalService,
                                     KbAlertService kbAlertService,
                                     KbChatFeedbackRepository feedbackRepository,
                                     ContentGapService contentGapService,
                                     KbGovernanceService kbGovernanceService) {
        this.usageStatService = usageStatService;
        this.kbEvalService = kbEvalService;
        this.kbAlertService = kbAlertService;
        this.feedbackRepository = feedbackRepository;
        this.contentGapService = contentGapService;
        this.kbGovernanceService = kbGovernanceService;
    }

    @Override
    public KbWeeklyReportView weekly(String libraryId, int days) {
        int period = Math.max(1, Math.min(90, days));
        Date from = new Date(System.currentTimeMillis() - period * 24L * 60 * 60 * 1000);

        // 用量/反馈跨空间；缺口/评测/告警仍按管理端公共库口径。
        UsageSummaryView usage = usageStatService.summary(null, period);
        long up = feedbackRepository.countByRatingAndCreatedDateAfter(RATING_UP, from);
        long down = feedbackRepository.countByRatingAndCreatedDateAfter(RATING_DOWN, from);
        long totalFeedback = up + down;

        List<DownQuestionView> downTop = new ArrayList<>();
        for (Object[] row : feedbackRepository.aggregateDownQuestionsAll(from)) {
            if (downTop.size() >= DOWN_TOP_LIMIT) {
                break;
            }
            downTop.add(new DownQuestionView(
                    (String) row[0],
                    row[1] instanceof Number n ? n.longValue() : 0L,
                    (String) row[2],
                    row[3] instanceof Date d ? d : null));
        }

        List<ContentGapView> gapTop = contentGapService.top(libraryId, GAP_TOP_LIMIT);
        List<KbEvalReportView> evalTrend = kbEvalService.trend(libraryId, period);
        long openAlerts = kbAlertService.page(libraryId, false, PageRequest.of(0, 1)).getTotalElements();

        var governance = kbGovernanceService.summary(libraryId, 180);
        return KbWeeklyReportView.builder()
                .governance(governance)
                .days(period)
                .generatedAt(new Date())
                .usage(usage)
                .feedbackUp(up)
                .feedbackDown(down)
                .downRate(totalFeedback == 0 ? 0d : (double) down / totalFeedback)
                .openAlerts(openAlerts)
                .downTopQuestions(downTop)
                .gapTop(gapTop)
                .evalTrend(evalTrend)
                .build();
    }
}
