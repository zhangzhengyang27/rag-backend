package org.example.quota.impl;

import org.example.quota.QuotaDailyCounter;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPooled;

import java.util.List;


/** Redis 实现：key = quota:{library}:{metric}:{yyyyMMdd}，自增并设 48h TTL 自动过期。 */
@Component
public class JedisQuotaDailyCounter implements QuotaDailyCounter {

    /** INCR 与 EXPIRE 原子执行（Lua，与 RedisLoginGuard 同模式）：
     *  两步分离时若进程在 INCR 后崩溃，会留下永不过期的计数 key，导致配额被永久占用。 */
    private static final String INCR_WITH_TTL =
            "local c = redis.call('INCR', KEYS[1]); redis.call('EXPIRE', KEYS[1], ARGV[1]); return c;";

    /** 日计数保留时长（秒）：跨天后即无意义，48h 覆盖时区边界。 */
    private static final long TTL_SECONDS = 60 * 60 * 48L;

    private final JedisPooled jedis;

    public JedisQuotaDailyCounter(JedisPooled kbJedis) {
        this.jedis = kbJedis;
    }

    @Override
    public long increment(String key) {
        Object value = jedis.eval(INCR_WITH_TTL, List.of(key), List.of(String.valueOf(TTL_SECONDS)));
        return value instanceof Number n ? n.longValue() : 0L;
    }

    @Override
    public long peek(String key) {
        String value = jedis.get(key);
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

}
