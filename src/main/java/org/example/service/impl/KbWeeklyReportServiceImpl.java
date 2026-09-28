package org.example.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.repository.KbChatFeedbackRepository;
import org.example.service.KbAlertService;
import org.example.service.KbEvalService;
import org.example.service.KbWeeklyReportService;
import org.example.service.UsageStatService;
import org.example.vo.DownQuestionView;
import org.example.vo.KbEvalReportView;
import org.example.vo.KbWeeklyReportView;
import org.example.vo.UsageSummaryView;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 运营周报实现：全部指标为实时聚合（不走缓存），规模为个人/小团队站点量级，
 * 一次调用 = 用量汇总 + 2 次反馈计数 + 1 次点踩聚合 + 评测趋势 + 告警计数，开销可忽略。
 */
@Slf4j
@Service
public class KbWeeklyReportServiceImpl implements KbWeeklyReportService {

    /** 点踩榜长度上限：周报聚焦最高频问题，长尾走 /admin/kb/feedback 明细页。 */
    private static final int DOWN_TOP_LIMIT = 10;

    private static final String RATING_UP = "UP";
    private static final String RATING_DOWN = "DOWN";

    private final UsageStatService usageStatService;
    private final KbEvalService kbEvalService;
    private final KbAlertService kbAlertService;
    private final KbChatFeedbackRepository feedbackRepository;

    public KbWeeklyReportServiceImpl(UsageStatService usageStatService,
                                     KbEvalService kbEvalService,
                                     KbAlertService kbAlertService,
                                     KbChatFeedbackRepository feedbackRepository) {
        this.usageStatService = usageStatService;
        this.kbEvalService = kbEvalService;
        this.kbAlertService = kbAlertService;
        this.feedbackRepository = feedbackRepository;
    }

    @Override
    public KbWeeklyReportView weekly(String libraryId, int days) {
        int period = Math.max(1, Math.min(90, days));
        Date from = new Date(System.currentTimeMillis() - period * 24L * 60 * 60 * 1000);

        UsageSummaryView usage = usageStatService.summary(libraryId, period);
        long up = feedbackRepository.countByLibraryIdAndRatingAndCreatedDateAfter(libraryId, RATING_UP, from);
        long down = feedbackRepository.countByLibraryIdAndRatingAndCreatedDateAfter(libraryId, RATING_DOWN, from);
        long totalFeedback = up + down;

        List<DownQuestionView> downTop = new ArrayList<>();
        for (Object[] row : feedbackRepository.aggregateDownQuestions(libraryId, from)) {
            if (downTop.size() >= DOWN_TOP_LIMIT) {
                break;
            }
            downTop.add(new DownQuestionView(
                    (String) row[0],
                    row[1] instanceof Number n ? n.longValue() : 0L,
                    (String) row[2],
                    row[3] instanceof Date d ? d : null));
        }

        List<KbEvalReportView> evalTrend = kbEvalService.trend(libraryId, period);
        long openAlerts = kbAlertService.page(libraryId, false, PageRequest.of(0, 1)).getTotalElements();

        return KbWeeklyReportView.builder()
                .days(period)
                .generatedAt(new Date())
                .usage(usage)
                .feedbackUp(up)
                .feedbackDown(down)
                .downRate(totalFeedback == 0 ? 0d : (double) down / totalFeedback)
                .openAlerts(openAlerts)
                .downTopQuestions(downTop)
                .evalTrend(evalTrend)
                .build();
    }
}
