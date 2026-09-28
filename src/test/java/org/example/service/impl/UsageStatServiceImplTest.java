package org.example.service.impl;

import org.example.entity.ChatUsageEntity;
import org.example.repository.ChatUsageRepository;
import org.example.service.UsageStatService;
import org.example.vo.ChatUsageView;
import org.example.vo.UsageSummaryView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用量统计服务单测：记录组装、汇总聚合与趋势、明细分页映射。
 */
@ExtendWith(MockitoExtension.class)
class UsageStatServiceImplTest {

    @Mock
    private ChatUsageRepository usageRepository;

    @InjectMocks
    private UsageStatServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UsageStatServiceImpl(usageRepository);
    }

    private ChatUsageEntity entity(long id, String userId, int input, int output, long elapsed,
                                   int hit, Date created) {
        ChatUsageEntity e = new ChatUsageEntity();
        e.setId(id);
        e.setLibraryId("default");
        e.setUserId(userId);
        e.setQuestion("问题");
        e.setInputTokens(input);
        e.setOutputTokens(output);
        e.setTotalTokens(input + output);
        e.setElapsedMs(elapsed);
        e.setHitCount(hit);
        e.setCreatedDate(created);
        return e;
    }

    @Test
    void recordAssemblesAndSavesEntity() {
        service.record("default", "u1", "conv", "问题", "回答内容", 10, 5, 1234L, 3);

        verify(usageRepository).save(argThat(e ->
                "default".equals(e.getLibraryId())
                        && "u1".equals(e.getUserId())
                        && "conv".equals(e.getConversationId())
                        && e.getInputTokens() == 10
                        && e.getOutputTokens() == 5
                        && e.getTotalTokens() == 15
                        && e.getElapsedMs() == 1234L
                        && e.getHitCount() == 3
                        && e.getAnswerCharCount() == 4));
    }

    @Test
    void recordSwallowsSaveError() {
        org.mockito.Mockito.doThrow(new RuntimeException("db down")).when(usageRepository).save(any());
        // 不应抛异常
        service.record("default", "u1", "conv", "q", "a", 1, 1, 1, 0);
    }

    @Test
    void summaryAggregatesAndBuildsTrendDesc() {
        Date today = new Date();
        Date yesterday = new Date(today.getTime() - 24 * 3600_000L);
        when(usageRepository.findByLibraryIdAndCreatedDateAfterOrderByIdAsc(any(), any()))
                .thenReturn(List.of(
                        entity(1, "u1", 10, 20, 1000, 3, yesterday),
                        entity(2, "u2", 20, 30, 2000, 5, today)));

        UsageSummaryView summary = service.summary("default", 30);

        assertThat(summary.getTotalQuestions()).isEqualTo(2);
        assertThat(summary.getTotalInputTokens()).isEqualTo(30);
        assertThat(summary.getTotalOutputTokens()).isEqualTo(50);
        assertThat(summary.getTotalTokens()).isEqualTo(80);
        assertThat(summary.getAvgLatencyMs()).isEqualTo(1500.0);
        assertThat(summary.getAvgHitCount()).isEqualTo(4.0);
        // 趋势按日期倒序（yyyy-MM-dd 字典序即时间序）
        assertThat(summary.getTrend()).hasSize(2);
        assertThat(summary.getTrend().get(0).getDate()).isGreaterThan(summary.getTrend().get(1).getDate());
        assertThat(summary.getTrend().get(0).getQuestions()).isEqualTo(1);
    }

    @Test
    void recentMapsPageToViews() {
        when(usageRepository.findByLibraryIdOrderByIdDesc(any(), any()))
                .thenReturn(new PageImpl<>(List.of(entity(7, "u1", 5, 5, 500, 2, new Date()))));

        Page<ChatUsageView> page = service.recent("default", PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(1);
        ChatUsageView view = page.getContent().get(0);
        assertThat(view.getId()).isEqualTo(7);
        assertThat(view.getUserId()).isEqualTo("u1");
        assertThat(view.getTotalTokens()).isEqualTo(10);
        assertThat(view.getHitCount()).isEqualTo(2);
    }
}
