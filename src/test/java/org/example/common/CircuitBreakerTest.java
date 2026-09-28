package org.example.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 熔断器单测：阈值打开、冷却后半开单探针、探针成败的状态流转、
 * 冷却期内陈旧成功回调不提前关闭、探针丢失后可自愈。
 */
class CircuitBreakerTest {

    @Test
    void opensAfterConsecutiveFailuresAndRejects() {
        CircuitBreaker cb = CircuitBreaker.withMillis(3, 60_000);

        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.allowRequest()).isTrue();
        cb.recordFailure();

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(cb.allowRequest()).isFalse();
    }

    @Test
    void staleSuccessDuringCooldownDoesNotCloseBreaker() {
        CircuitBreaker cb = CircuitBreaker.withMillis(1, 60_000);
        cb.recordFailure();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // 早已放行的慢请求在熔断后才回调成功：不能把熔断器提前关闭
        cb.recordSuccess();

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(cb.allowRequest()).isFalse();
    }

    @Test
    void halfOpenAdmitsSingleProbeThenRejectsUntilProbeReturns() throws Exception {
        CircuitBreaker cb = CircuitBreaker.withMillis(1, 50);
        cb.recordFailure();

        Thread.sleep(60); // 越过冷却期
        assertThat(cb.allowRequest()).isTrue(); // 探针放行
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(cb.allowRequest()).isFalse(); // 半开期其余请求快速失败

        cb.recordSuccess();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(cb.allowRequest()).isTrue();
    }

    @Test
    void probeFailureReopensBreaker() throws Exception {
        CircuitBreaker cb = CircuitBreaker.withMillis(1, 50);
        cb.recordFailure();
        Thread.sleep(60);
        assertThat(cb.allowRequest()).isTrue();

        cb.recordFailure();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(cb.allowRequest()).isFalse();
    }

    @Test
    void lostProbeSelfHealsAfterWindow() throws Exception {
        CircuitBreaker cb = CircuitBreaker.withMillis(1, 50);
        cb.recordFailure();
        Thread.sleep(60);
        assertThat(cb.allowRequest()).isTrue(); // 第一个探针（回调丢失，如客户端取消 SSE）

        Thread.sleep(60); // 探针窗口超时：状态机放行新探针，避免永久卡在半开
        assertThat(cb.allowRequest()).isTrue();
    }

    @Test
    void successResetsConsecutiveFailureCount() {
        CircuitBreaker cb = CircuitBreaker.withMillis(3, 60_000);

        cb.recordFailure();
        cb.recordFailure();
        cb.recordSuccess();
        cb.recordFailure();

        // 成功清零计数：此后只有 1 次连续失败，不应打开
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
