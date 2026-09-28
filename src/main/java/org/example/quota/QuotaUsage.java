package org.example.quota;

/**
 * 空间配额使用快照：三项配额的当前用量与上限。
 * limit 为 0/null 表示不限制；日用量取当日计数器当前值。
 */
public record QuotaUsage(
        long docUsed,
        long docLimit,
        long retrievalUsed,
        long retrievalDailyLimit,
        long chatUsed,
        long chatDailyLimit) {
}
