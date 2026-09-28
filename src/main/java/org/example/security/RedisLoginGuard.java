package org.example.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 登录防爆破守卫的 Redis 实现（生产默认）：失败计数与锁定标记存 Redis 并带 TTL，
 * 多实例部署共享状态；同时支撑管理端「锁定列表查看 / 解锁」的安全可观测能力。
 * <p>
 * 键设计：{@code login:fail:{username}}（INCR+EXPIRE 滑动窗口计数）、
 * {@code login:lock:{username}}（达到阈值后 SETEX，存在即锁定）。
 * 与向量前缀空间（lostsystem:kbdoc:*）隔离，互不影响对账清扫。
 * </p>
 */
@Slf4j
@Component
public class RedisLoginGuard implements LoginGuard {

    private static final String FAIL_PREFIX = "login:fail:";
    private static final String LOCK_PREFIX = "login:lock:";

    private final JedisPooled jedis;
    private final int maxFailures;
    private final int lockSeconds;

    public RedisLoginGuard(JedisPooled kbJedis,
                           @Value("${app.auth.login-max-failures:5}") int maxFailures,
                           @Value("${app.auth.login-lock-seconds:600}") long lockSeconds) {
        this.jedis = kbJedis;
        this.maxFailures = Math.max(1, maxFailures);
        // 0 表示锁定窗口为 0（等同于不锁定），便于测试与显式关闭
        this.lockSeconds = (int) Math.max(0, lockSeconds);
    }

    @Override
    public boolean isLocked(String username) {
        if (username == null || username.isBlank() || lockSeconds == 0) {
            return false;
        }
        try {
            return jedis.exists(LOCK_PREFIX + username);
        } catch (Exception e) {
            // Redis 异常时放行（不因可观测组件故障阻断登录），由失败计数兜底
            log.warn("登录锁定状态查询失败，放行处理 - username: {}, err: {}", username, e.getMessage());
            return false;
        }
    }

    /** INCR 与 EXPIRE 原子执行（Lua）：避免两步之间进程崩溃留下无 TTL 的永久计数。 */
    private static final String INCR_WITH_TTL =
            "local c = redis.call('INCR', KEYS[1]); redis.call('EXPIRE', KEYS[1], ARGV[1]); return c;";

    @Override
    public void recordFailure(String username) {
        if (username == null || username.isBlank() || lockSeconds == 0) {
            return;
        }
        try {
            String failKey = FAIL_PREFIX + username;
            Object count = jedis.eval(INCR_WITH_TTL, java.util.List.of(failKey), java.util.List.of(String.valueOf(lockSeconds)));
            if (count instanceof Number n && n.longValue() >= maxFailures) {
                jedis.setex(LOCK_PREFIX + username, lockSeconds, String.valueOf(System.currentTimeMillis()));
                log.info("账号登录失败达阈值已锁定 - username: {}, failures: {}, lockSeconds: {}",
                        username, n.longValue(), lockSeconds);
            }
        } catch (Exception e) {
            log.warn("登录失败计数写入失败 - username: {}, err: {}", username, e.getMessage());
        }
    }

    @Override
    public void recordSuccess(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        try {
            jedis.del(FAIL_PREFIX + username, LOCK_PREFIX + username);
        } catch (Exception e) {
            log.warn("登录成功清理计数失败 - username: {}, err: {}", username, e.getMessage());
        }
    }

    @Override
    public List<LockedAccount> listLocked() {
        List<LockedAccount> result = new ArrayList<>();
        try {
            String cursor = "0";
            ScanParams params = new ScanParams().match(LOCK_PREFIX + "*").count(200);
            do {
                ScanResult<String> scan = jedis.scan(cursor, params);
                cursor = scan.getCursor();
                for (String key : scan.getResult()) {
                    String username = key.substring(LOCK_PREFIX.length());
                    long pttl = jedis.pttl(key);
                    if (pttl <= 0) {
                        continue;
                    }
                    String fail = jedis.get(FAIL_PREFIX + username);
                    int failures = fail == null ? maxFailures : Integer.parseInt(fail);
                    result.add(new LockedAccount(username, failures, pttl / 1000));
                }
            } while (!"0".equals(cursor));
        } catch (Exception e) {
            log.warn("锁定账号列表查询失败 - err: {}", e.getMessage());
        }
        return result;
    }

    @Override
    public void unlock(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        try {
            jedis.del(LOCK_PREFIX + username, FAIL_PREFIX + username);
        } catch (Exception e) {
            log.warn("解锁账号失败 - username: {}, err: {}", username, e.getMessage());
        }
    }
}
