package org.example.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 轻量级熔断器：保护外部依赖（LLM）调用。
 * 状态机：CLOSED（正常）→ 连续失败达阈值进入 OPEN（快速失败）→ 冷却期后 HALF_OPEN（放行探针）→
 * 探针成功回 CLOSED，失败回 OPEN。
 */
@Slf4j
@Component
public class CircuitBreaker {

    public enum State {
        CLOSED, OPEN, HALF_OPEN
    }

    private final int failureThreshold;
    private final long cooldownMillis;
    private final Object lock = new Object();

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private long openedAt;
    /** 半开状态下是否已有探针在途：保证 OPEN → HALF_OPEN 只放行一个探针请求 */
    private boolean probeInFlight;
    /** 进入半开的时间：探针丢失（如客户端取消 SSE 流，成功/失败回调都不触发）时据此放行新探针，避免永久卡死 */
    private long halfOpenAt;

    /** 两个构造器并存（含测试工厂），显式指定本构造器为 Spring 注入入口。 */
    @org.springframework.beans.factory.annotation.Autowired
    public CircuitBreaker(@Value("${app.breaker.failure-threshold:5}") int failureThreshold,
                          @Value("${app.breaker.cooldown-seconds:30}") long cooldownSeconds) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.cooldownMillis = Math.max(1, cooldownSeconds) * 1000L;
    }

    /** 测试用工厂：以毫秒直配冷却期，便于验证半开/自愈流转而无需真实等待。 */
    static CircuitBreaker withMillis(int failureThreshold, long cooldownMillis) {
        return new CircuitBreaker(failureThreshold, cooldownMillis, true);
    }

    private CircuitBreaker(int failureThreshold, long cooldownMillis, boolean millisDirect) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.cooldownMillis = Math.max(1, cooldownMillis);
    }

    /**
     * 是否放行本次请求。
     */
    public boolean allowRequest() {
        synchronized (lock) {
            if (state == State.OPEN) {
                if (System.currentTimeMillis() - openedAt >= cooldownMillis) {
                    state = State.HALF_OPEN;
                    probeInFlight = true;
                    halfOpenAt = System.currentTimeMillis();
                    log.info("熔断器进入半开状态，放行探针请求");
                    return true;
                }
                return false;
            }
            if (state == State.HALF_OPEN) {
                long inHalfOpen = System.currentTimeMillis() - halfOpenAt;
                // 探针在途且未超时：其余请求继续快速失败，避免半开期被打穿；
                // 探针丢失（超时无回调）则放行新探针，保证状态机可自愈
                if (probeInFlight && inHalfOpen < cooldownMillis) {
                    return false;
                }
                probeInFlight = true;
                halfOpenAt = System.currentTimeMillis();
                return true;
            }
            return true;
        }
    }

    public void recordSuccess() {
        synchronized (lock) {
            consecutiveFailures = 0;
            if (state == State.HALF_OPEN) {
                log.info("熔断器探针成功，恢复关闭状态");
                state = State.CLOSED;
                probeInFlight = false;
            } else if (state == State.OPEN) {
                // 冷却期内的陈旧成功回调（早已放行的慢请求）：只清零计数，不能提前关闭熔断
                probeInFlight = false;
            }
        }
    }

    public void recordFailure() {
        synchronized (lock) {
            if (state == State.HALF_OPEN) {
                state = State.OPEN;
                openedAt = System.currentTimeMillis();
                probeInFlight = false;
                log.warn("熔断器探针失败，回到打开状态");
                return;
            }
            consecutiveFailures++;
            if (consecutiveFailures >= failureThreshold) {
                state = State.OPEN;
                openedAt = System.currentTimeMillis();
                log.warn("连续失败 {} 次，熔断器打开（冷却 {} 秒）", consecutiveFailures, cooldownMillis / 1000);
            }
        }
    }

    public State getState() {
        synchronized (lock) {
            return state;
        }
    }
}
