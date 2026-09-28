package org.example.quota.impl;

import org.example.quota.QuotaDailyCounter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 内存实现（测试用）。 */
public class InMemoryQuotaDailyCounter implements QuotaDailyCounter {

    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

    @Override
    public long increment(String key) {
        return counters.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }

    @Override
    public long peek(String key) {
        AtomicLong v = counters.get(key);
        return v == null ? 0L : v.get();
    }
}
