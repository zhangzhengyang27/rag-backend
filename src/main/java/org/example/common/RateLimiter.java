package org.example.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 轻量级令牌桶限流器（单实例内存实现）。
 * <p>
 * 以 userId 为维度进行配额控制，防止单个用户打爆 LLM / 数据库配额。
 * 令牌桶：容量 burst 上限 + 按 rate 匀速补充，突发可容忍、长期受限。
 * </p>
 * <p>
 * 注意：本实现为单实例方案。多实例水平扩展时应替换为分布式限流
 * （如 Bucket4j + Redis），保证配额在集群内共享。
 * </p>
 */
@Component
public class RateLimiter {

    /** 桶空闲多久后可被清扫回收（无活动的用户桶不再占用内存）。 */
    private static final long IDLE_BUCKET_NANOS = TimeUnit.HOURS.toNanos(1);

    private final int capacity;
    private final double refillPerSecond;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(@Value("${app.limit.chat-rate-per-minute:60}") int ratePerMinute,
                       @Value("${app.limit.chat-burst-capacity:20}") int burstCapacity) {
        this.capacity = Math.max(1, burstCapacity);
        this.refillPerSecond = Math.max(0, ratePerMinute) / 60.0;
    }

    /**
     * 尝试获取一个令牌。
     *
     * @param key 限流维度（如 userId）
     * @return true=放行，false=触发限流
     */
    public boolean tryAcquire(String key) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, refillPerSecond));
        return bucket.tryAcquire();
    }

    /**
     * 定期清扫长时间无活动的桶：key 为登录用户维度，长期运行下 map 会随历史用户无上限增长。
     * lastRefillNanos 在每次 tryAcquire 时刷新，可直接作为"最近活跃"依据。
     */
    @Scheduled(initialDelay = 600_000, fixedDelay = 600_000)
    public void sweepIdleBuckets() {
        long now = System.nanoTime();
        buckets.values().removeIf(b -> now - b.lastRefillNanos > IDLE_BUCKET_NANOS);
    }

    /**
     * 令牌桶：容量 + 每秒匀速补充。
     */
    private static class Bucket {

        private final int capacity;
        private final double refillPerSecond;
        private volatile double tokens;
        private volatile long lastRefillNanos;

        Bucket(int capacity, double refillPerSecond) {
            this.capacity = capacity;
            this.refillPerSecond = refillPerSecond;
            this.tokens = capacity;
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized boolean tryAcquire() {
            long now = System.nanoTime();
            double elapsed = (now - lastRefillNanos) / 1_000_000_000.0;
            lastRefillNanos = now;
            tokens = Math.min(capacity, tokens + elapsed * refillPerSecond);
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
