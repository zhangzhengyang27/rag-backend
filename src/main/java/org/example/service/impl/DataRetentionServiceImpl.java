package org.example.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.repository.AuditLogRepository;
import org.example.repository.ChatUsageRepository;
import org.example.repository.RetrievalTraceRepository;
import org.example.service.DataRetentionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

/**
 * 观测数据保留清理实现：三张只增不清的观测表各自独立保留天数，
 * 天数 <=0 表示该表不清理。单条 bulk delete 在 NAS 规模（日增千行级）下足够，
 * 未来数据量上涨可再按 id 分批。
 */
@Slf4j
@Service
public class DataRetentionServiceImpl implements DataRetentionService {

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    private final RetrievalTraceRepository traceRepository;
    private final ChatUsageRepository usageRepository;
    private final AuditLogRepository auditLogRepository;

    @Value("${app.data-retention.trace-days:90}")
    private int traceDays;

    @Value("${app.data-retention.usage-days:180}")
    private int usageDays;

    @Value("${app.data-retention.audit-days:365}")
    private int auditDays;

    public DataRetentionServiceImpl(RetrievalTraceRepository traceRepository,
                                    ChatUsageRepository usageRepository,
                                    AuditLogRepository auditLogRepository) {
        this.traceRepository = traceRepository;
        this.usageRepository = usageRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @Override
    @Transactional
    public RetentionSummary purgeOlderThan(Date now) {
        long traces = purge("检索追踪", traceDays, traceRepository::deleteAllByCreatedDateBefore, now);
        long usages = purge("聊天用量", usageDays, usageRepository::deleteAllByCreatedDateBefore, now);
        long audits = purge("审计日志", auditDays, auditLogRepository::deleteAllByCreatedDateBefore, now);
        return new RetentionSummary(traces, usages, audits);
    }

    /** 按保留天数清理单表；天数 <=0 跳过。返回删除行数。 */
    private long purge(String label, int days, DateCleanup cleanup, Date now) {
        if (days <= 0) {
            return 0;
        }
        Date cutoff = new Date(now.getTime() - days * DAY_MS);
        long deleted = cleanup.deleteBefore(cutoff);
        if (deleted > 0) {
            log.info("观测数据保留清理完成 - 表: {}, 保留: {} 天, 截止: {}, 删除: {} 行", label, days, cutoff, deleted);
        }
        return deleted;
    }

    @FunctionalInterface
    private interface DateCleanup {
        long deleteBefore(Date cutoff);
    }
}
