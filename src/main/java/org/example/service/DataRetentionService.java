package org.example.service;

import java.util.Date;

/**
 * 观测数据保留清理服务：按配置的保留天数删除检索追踪/聊天用量/审计日志，
 * 防止只增不清的观测表无限增长（schema 见 db/migration，配置见 app.data-retention.*）。
 */
public interface DataRetentionService {

    /**
     * 以给定当前时间计算各表截止时间并执行清理。
     *
     * @param now 当前时间（截止时间 = now - 保留天数；测试可注入固定值）
     * @return 各表删除行数汇总
     */
    RetentionSummary purgeOlderThan(Date now);

    /** 清理结果汇总（行数）。 */
    record RetentionSummary(long tracesDeleted, long usagesDeleted, long auditsDeleted) {
    }
}
