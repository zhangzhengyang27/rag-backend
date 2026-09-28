package org.example.security;

import java.util.List;

/**
 * 登录防爆破守卫：按 username 记录连续失败次数，达到阈值后在锁定窗口内拒绝该账号登录，
 * 成功登录即清零。生产实现为 Redis（多实例共享状态，见 {@link RedisLoginGuard}），
 * 单测可用内存实现（InMemoryLoginGuard）。
 */
public interface LoginGuard {

    /** 该账号是否处于锁定窗口内。 */
    boolean isLocked(String username);

    /** 记录一次登录失败；窗口外的旧计数自动作废。 */
    void recordFailure(String username);

    /** 登录成功：清零该账号计数。 */
    void recordSuccess(String username);

    /** 当前全部锁定中的账号（管理端安全可观测）。 */
    List<LockedAccount> listLocked();

    /** 管理员解锁指定账号（清空失败计数与锁定标记）。 */
    void unlock(String username);

    /** 锁定中的账号视图。 */
    record LockedAccount(String username, int failureCount, long remainingSeconds) {
    }
}
