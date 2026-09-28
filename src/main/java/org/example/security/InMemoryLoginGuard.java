package org.example.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录防爆破守卫的内存实现（单元测试用，不注册为 Bean；
 * 生产注入 Redis 实现见 {@link RedisLoginGuard}）。
 * <p>
 * 按 username 记录连续失败次数：失败达到阈值后在锁定窗口内拒绝该账号登录；
 * 窗口自最后一次失败起算（滑动），成功登录即清零。
 * </p>
 */
public class InMemoryLoginGuard implements LoginGuard {

    private final int maxFailures;
    private final long lockMillis;
    private final ConcurrentHashMap<String, Failure> failures = new ConcurrentHashMap<>();

    public InMemoryLoginGuard(int maxFailures, long lockSeconds) {
        this.maxFailures = Math.max(1, maxFailures);
        // 0 表示锁定窗口为 0（等同于不锁定），便于测试与显式关闭
        this.lockMillis = Math.max(0, lockSeconds) * 1000L;
    }

    @Override
    public boolean isLocked(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        Failure f = failures.get(username);
        if (f == null) {
            return false;
        }
        if (isExpired(f)) {
            failures.remove(username, f);
            return false;
        }
        return f.count() >= maxFailures;
    }

    @Override
    public void recordFailure(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        failures.compute(username, (k, f) -> {
            if (f == null || isExpired(f, now)) {
                return new Failure(1, now + lockMillis);
            }
            return new Failure(f.count() + 1, now + lockMillis);
        });
    }

    @Override
    public void recordSuccess(String username) {
        if (username != null) {
            failures.remove(username);
        }
    }

    @Override
    public List<LoginGuard.LockedAccount> listLocked() {
        long now = System.currentTimeMillis();
        return failures.entrySet().stream()
                .filter(e -> !isExpired(e.getValue(), now) && e.getValue().count() >= maxFailures)
                .map(e -> new LoginGuard.LockedAccount(e.getKey(), e.getValue().count(),
                        Math.max(0, (e.getValue().expiresAt() - now) / 1000)))
                .toList();
    }

    @Override
    public void unlock(String username) {
        if (username != null) {
            failures.remove(username);
        }
    }

    private boolean isExpired(Failure f) {
        return isExpired(f, System.currentTimeMillis());
    }

    private boolean isExpired(Failure f, long now) {
        return lockMillis == 0 || now >= f.expiresAt();
    }

    /** 不可变失败记录：连续失败次数 + 锁定截止时间。 */
    private record Failure(int count, long expiresAt) {
    }
}
