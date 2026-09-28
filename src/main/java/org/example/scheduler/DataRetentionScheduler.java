package org.example.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.example.service.DataRetentionService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * 观测数据保留清理定时任务：每日凌晨执行（默认 03:30，见 app.data-retention.cron），
 * 删除超过保留期的检索追踪/聊天用量/审计日志。失败只记日志，不影响次日重试。
 */
@Slf4j
@Component
public class DataRetentionScheduler {

    private final DataRetentionService dataRetentionService;

    public DataRetentionScheduler(DataRetentionService dataRetentionService) {
        this.dataRetentionService = dataRetentionService;
    }

    @Scheduled(cron = "${app.data-retention.cron:0 30 3 * * *}")
    public void purge() {
        try {
            DataRetentionService.RetentionSummary summary = dataRetentionService.purgeOlderThan(new Date());
            if (summary.tracesDeleted() + summary.usagesDeleted() + summary.auditsDeleted() > 0) {
                log.info("数据保留清理任务完成 - 追踪: {}, 用量: {}, 审计: {}",
                        summary.tracesDeleted(), summary.usagesDeleted(), summary.auditsDeleted());
            }
        } catch (Exception e) {
            log.error("数据保留清理任务失败（不影响次日重试）", e);
        }
    }
}
