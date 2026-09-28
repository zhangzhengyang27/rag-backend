package org.example.quota;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** 按 key 累加的日计数器（隔离 Redis 细节，便于单测替换）。 */
public interface QuotaDailyCounter {

    DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 对 key 自增并返回当前计数。 */
    long increment(String key);

    /** 读取 key 当前计数（不累加），无计数返回 0。供配额余量查询。 */
    long peek(String key);

    /** 生成当日计数 key：{prefix}:{libraryId}:{metric}:{yyyyMMdd}（供调用方组装，天然 48h 过期）。 */
    static String dayKey(String prefix, String libraryId, String metric) {
        return prefix + libraryId + ":" + metric + ":" + LocalDate.now().format(DAY);
    }
}
