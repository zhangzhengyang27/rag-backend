package org.example.service.impl;

import org.example.repository.KbChatFeedbackRepository;
import org.example.service.ContentGapService;
import org.example.service.KbAlertService;
import org.example.service.KbEvalService;
import org.example.service.KbGovernanceService;
import org.example.service.UsageStatService;
import org.example.vo.ContentGapView;
import org.example.vo.KbAlertView;
import org.example.vo.KbEvalReportView;
import org.example.vo.KbGovernanceSummaryView;
import org.example.vo.KbWeeklyReportView;
import org.example.vo.UsageSummaryView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运营周报聚合单测：用量/反馈/治理/缺口/评测/告警多源拼装、点踩率计算与越界天数收敛。
 * 管理端周报跨空间统计，因此用量与反馈走全局口径（libraryId=null）。
 */
@ExtendWith(MockitoExtension.class)
class KbWeeklyReportServiceImplTest {

    @Mock
    private UsageStatService usageStatService;
    @Mock
    private KbEvalService kbEvalService;
    @Mock
    private KbAlertService kbAlertService;
    @Mock
    private KbChatFeedbackRepository feedbackRepository;
    @Mock
    private ContentGapService contentGapService;
    @Mock
    private KbGovernanceService kbGovernanceService;

    @InjectMocks
    private KbWeeklyReportServiceImpl service;

    private void stubCommon(int period) {
        when(usageStatService.summary(null, period))
                .thenReturn(UsageSummaryView.builder().totalQuestions(42).totalTokens(1024).build());
        when(feedbackRepository.countByRatingAndCreatedDateAfter(eq("UP"), any(Date.class))).thenReturn(8L);
        when(feedbackRepository.countByRatingAndCreatedDateAfter(eq("DOWN"), any(Date.class))).thenReturn(2L);
        when(kbEvalService.trend("default", period)).thenReturn(List.of(new KbEvalReportView()));
        when(kbAlertService.page("default", false, PageRequest.of(0, 1)))
                .thenReturn(new PageImpl<>(List.of(KbAlertView.builder().build()), PageRequest.of(0, 1), 3));
        when(contentGapService.top("default", 10)).thenReturn(List.of());
        when(kbGovernanceService.summary("default", 180)).thenReturn(KbGovernanceSummaryView.builder().build());
    }

    @Test
    void weeklyComposesAllSourcesAndComputesDownRate() {
        stubCommon(30);
        when(feedbackRepository.aggregateDownQuestionsAll(any(Date.class))).thenReturn(List.of(
                new Object[]{"如何配置 Redis 持久化", 3L, "回答过时", new Date()},
                new Object[]{"JWT 刷新机制是什么", 2L, null, new Date()},
                new Object[]{"为什么索引会失效", 1L, "没看懂", null}));

        KbWeeklyReportView report = service.weekly("default", 30);

        assertThat(report.getDays()).isEqualTo(30);
        assertThat(report.getUsage().getTotalQuestions()).isEqualTo(42);
        assertThat(report.getFeedbackUp()).isEqualTo(8);
        assertThat(report.getFeedbackDown()).isEqualTo(2);
        assertThat(report.getDownRate()).isEqualTo(0.2);
        assertThat(report.getOpenAlerts()).isEqualTo(3);
        assertThat(report.getEvalTrend()).hasSize(1);
        assertThat(report.getDownTopQuestions()).hasSize(3);
        assertThat(report.getDownTopQuestions().get(0).count()).isEqualTo(3);
        assertThat(report.getDownTopQuestions().get(0).lastComment()).isEqualTo("回答过时");
        assertThat(report.getDownTopQuestions().get(1).lastComment()).isNull();
        assertThat(report.getDownTopQuestions().get(2).lastAt()).isNull();
        verify(usageStatService).summary(null, 30);
    }

    @Test
    void weeklyTruncatesDownQuestionsToTop10AndClampsDays() {
        stubCommon(90);
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            rows.add(new Object[]{"问题" + i, (long) (25 - i), null, new Date()});
        }
        when(feedbackRepository.aggregateDownQuestionsAll(any(Date.class))).thenReturn(rows);

        KbWeeklyReportView report = service.weekly("default", 500);

        assertThat(report.getDays()).isEqualTo(90);
        assertThat(report.getDownTopQuestions()).hasSize(10);
        assertThat(report.getDownTopQuestions().get(0).question()).isEqualTo("问题0");
    }

    @Test
    void downRateIsZeroWhenNoFeedback() {
        when(usageStatService.summary(null, 7))
                .thenReturn(UsageSummaryView.builder().totalQuestions(0).build());
        when(feedbackRepository.countByRatingAndCreatedDateAfter(eq("UP"), any(Date.class))).thenReturn(0L);
        when(feedbackRepository.countByRatingAndCreatedDateAfter(eq("DOWN"), any(Date.class))).thenReturn(0L);
        when(kbEvalService.trend("default", 7)).thenReturn(List.of());
        when(kbAlertService.page("default", false, PageRequest.of(0, 1)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 1), 0));
        when(contentGapService.top("default", 10)).thenReturn(List.of());
        when(kbGovernanceService.summary("default", 180)).thenReturn(KbGovernanceSummaryView.builder().build());
        when(feedbackRepository.aggregateDownQuestionsAll(any(Date.class))).thenReturn(List.of());

        KbWeeklyReportView report = service.weekly("default", 7);

        assertThat(report.getDownRate()).isZero();
        assertThat(report.getDownTopQuestions()).isEmpty();
        assertThat(report.getGapTop()).isEmpty();
    }
}
