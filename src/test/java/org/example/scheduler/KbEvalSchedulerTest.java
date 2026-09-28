package org.example.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.entity.KbEvalReportEntity;
import org.example.repository.KbEvalReportRepository;
import org.example.service.KbEvalService;
import org.example.vo.KbEvalResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 定时评测调度单测：对公共知识库（default）评测落库、当天幂等跳过、失败不落库。
 */
@ExtendWith(MockitoExtension.class)
class KbEvalSchedulerTest {

    @Mock
    private KbEvalService kbEvalService;

    @Mock
    private KbEvalReportRepository reportRepository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private KbEvalScheduler scheduler;

    @Test
    void dailyEvalPersistsReportForPublicSpace() throws Exception {
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        when(reportRepository.findByDateAndLibraryId(any(), any())).thenReturn(Optional.empty());
        KbEvalResult result = new KbEvalResult(2, 1, 1, 0.5, 0.25, 0.8, java.util.List.of());
        when(kbEvalService.run("default", null, 5)).thenReturn(result);

        scheduler.runDailyEval();

        verify(kbEvalService).run("default", null, 5);
        var captor = org.mockito.ArgumentCaptor.forClass(KbEvalReportEntity.class);
        verify(reportRepository).save(captor.capture());
        KbEvalReportEntity report = captor.getValue();
        assertThat(report.getDate()).isNotBlank();
        assertThat(report.getLibraryId()).isEqualTo("default");
        assertThat(report.getCaseCount()).isEqualTo(2);
        assertThat(report.getHitRate()).isEqualTo(0.5);
        assertThat(report.getMrr()).isEqualTo(0.25);
        assertThat(report.getNdcg()).isEqualTo(0.8);
        assertThat(report.getSnapshot()).isEqualTo("{}");
    }

    @Test
    void dailyEvalSkipsWhenReportExists() {
        when(reportRepository.findByDateAndLibraryId(any(), any())).thenReturn(Optional.of(new KbEvalReportEntity()));

        scheduler.runDailyEval();

        verify(kbEvalService, never()).run(any(), any(), any());
        verify(reportRepository, never()).save(any());
    }

    @Test
    void evalFailureDoesNotThrow() {
        when(reportRepository.findByDateAndLibraryId(any(), any())).thenReturn(Optional.empty());
        when(kbEvalService.run("default", null, 5)).thenThrow(new IllegalStateException("boom"));

        scheduler.runDailyEval();

        verify(reportRepository, never()).save(any());
    }
}
