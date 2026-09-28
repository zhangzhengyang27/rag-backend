package org.example.quota.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.JedisPooled;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 配额日计数器单测：INCR 与 EXPIRE 必须经 Lua 原子执行——
 * 两步分离时进程在中间崩溃会留下永不过期的计数 key，导致当日配额被永久占用。
 */
@ExtendWith(MockitoExtension.class)
class JedisQuotaDailyCounterTest {

    @Mock
    private JedisPooled jedis;

    @Test
    void incrementUsesAtomicLuaScriptWithTtl() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(3L);
        JedisQuotaDailyCounter counter = new JedisQuotaDailyCounter(jedis);

        long value = counter.increment("quota:default:chat:20260928");

        assertThat(value).isEqualTo(3L);
        verify(jedis).eval(anyString(), eq(List.of("quota:default:chat:20260928")),
                eq(List.of(String.valueOf(60 * 60 * 48L))));
        // 不允许出现分离的 INCR/EXPIRE 调用
        verify(jedis, org.mockito.Mockito.never()).incr(anyString());
        verify(jedis, org.mockito.Mockito.never()).expire(anyString(), org.mockito.ArgumentMatchers.any(long.class));
    }

    @Test
    void peekTreatsMissingOrCorruptValueAsZero() {
        JedisQuotaDailyCounter counter = new JedisQuotaDailyCounter(jedis);
        when(jedis.get("quota:default:chat:20260928")).thenReturn(null).thenReturn("garbage");

        assertThat(counter.peek("quota:default:chat:20260928")).isZero();
        assertThat(counter.peek("quota:default:chat:20260928")).isZero();
    }
}
