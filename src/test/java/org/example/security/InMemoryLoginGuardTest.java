package org.example.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 登录防爆破守卫单测：阈值锁定、成功清零、窗口过期自动解锁。
 */
class InMemoryLoginGuardTest {

    @Test
    void locksAfterThresholdFailures() {
        InMemoryLoginGuard guard = new InMemoryLoginGuard(3, 600);

        assertThat(guard.isLocked("alice")).isFalse();
        guard.recordFailure("alice");
        guard.recordFailure("alice");
        assertThat(guard.isLocked("alice")).isFalse();
        guard.recordFailure("alice");
        assertThat(guard.isLocked("alice")).isTrue();
    }

    @Test
    void successResetsFailureCount() {
        InMemoryLoginGuard guard = new InMemoryLoginGuard(3, 600);

        guard.recordFailure("alice");
        guard.recordFailure("alice");
        guard.recordSuccess("alice");
        guard.recordFailure("alice");

        assertThat(guard.isLocked("alice")).isFalse();
    }

    @Test
    void zeroLockWindowNeverLocks() {
        // lockSeconds=0 等同于关闭锁定（窗口立即过期），供显式关闭与测试
        InMemoryLoginGuard guard = new InMemoryLoginGuard(3, 0);

        for (int i = 0; i < 10; i++) {
            guard.recordFailure("alice");
        }

        assertThat(guard.isLocked("alice")).isFalse();
    }

    @Test
    void accountsAreIsolated() {
        InMemoryLoginGuard guard = new InMemoryLoginGuard(1, 600);

        guard.recordFailure("alice");

        assertThat(guard.isLocked("alice")).isTrue();
        assertThat(guard.isLocked("bob")).isFalse();
    }

    @Test
    void blankUsernameIsIgnored() {
        InMemoryLoginGuard guard = new InMemoryLoginGuard(1, 600);

        guard.recordFailure(" ");
        guard.recordFailure(null);

        assertThat(guard.isLocked("")).isFalse();
        assertThat(guard.isLocked(null)).isFalse();
    }
}
