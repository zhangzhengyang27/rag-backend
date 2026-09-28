package org.example.service.impl;

import org.example.repository.AuditLogRepository;
import org.example.repository.ChatUsageRepository;
import org.example.repository.RetrievalTraceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 观测数据保留清理单测：按保留天数计算截止时间并批量删除；
 * 天数配置为 0（不清理）时不得触碰对应仓库。
 */
@ExtendWith(MockitoExtension.class)
class DataRetentionServiceImplTest {

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    @Mock
    private RetrievalTraceRepository traceRepository;
    @Mock
    private ChatUsageRepository usageRepository;
    @Mock
    private AuditLogRepository auditLogRepository;

    @InjectMocks
    private DataRetentionServiceImpl service;

    private final Date now = new Date(1_700_000_000_000L);

    @BeforeEach
    void setUpDefaults() {
        ReflectionTestUtils.setField(service, "traceDays", 90);
        ReflectionTestUtils.setField(service, "usageDays", 180);
        ReflectionTestUtils.setField(service, "auditDays", 365);
    }

    @Test
    void purgeDeletesEachTableWithItsOwnCutoff() {
        when(traceRepository.deleteAllByCreatedDateBefore(any())).thenReturn(7L);
        when(usageRepository.deleteAllByCreatedDateBefore(any())).thenReturn(0L);
        when(auditLogRepository.deleteAllByCreatedDateBefore(any())).thenReturn(3L);

        DataRetentionServiceImpl.RetentionSummary summary = service.purgeOlderThan(now);

        verify(traceRepository).deleteAllByCreatedDateBefore(new Date(now.getTime() - 90 * DAY_MS));
        verify(usageRepository).deleteAllByCreatedDateBefore(new Date(now.getTime() - 180 * DAY_MS));
        verify(auditLogRepository).deleteAllByCreatedDateBefore(new Date(now.getTime() - 365 * DAY_MS));
        assertThat(summary.tracesDeleted()).isEqualTo(7L);
        assertThat(summary.usagesDeleted()).isZero();
        assertThat(summary.auditsDeleted()).isEqualTo(3L);
    }

    @Test
    void zeroDaysDisablesCleanupForThatTable() {
        ReflectionTestUtils.setField(service, "traceDays", 0);

        service.purgeOlderThan(now);

        verify(traceRepository, never()).deleteAllByCreatedDateBefore(any());
        verify(usageRepository).deleteAllByCreatedDateBefore(any());
        verify(auditLogRepository).deleteAllByCreatedDateBefore(any());
    }
}
