package org.example.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.config.RuntimeConfigStore;
import org.example.entity.UserEntity;
import org.example.library.KbLibrary;
import org.example.quota.QuotaDailyCounter;
import org.example.repository.KnowledgeDocumentRepository;
import org.example.repository.UserRepository;
import org.example.service.AdminUserService;
import org.example.service.AuditLogService;
import org.example.vo.UserQuotaView;
import org.example.vo.UserView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 管理端用户与配额管理实现。
 * 安全边界：引导管理员（app.admin.usernames）不可降级/禁用；不可禁用或降级自己；
 * 所有变更均写入审计日志（USER_ROLE / USER_STATUS / USER_RESET_PWD / UPDATE_QUOTA）。
 */
@Slf4j
@Service
public class AdminUserServiceImpl implements AdminUserService {

    private static final String ROLE_USER = "USER";
    private static final String ROLE_ADMIN = "ADMIN";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";

    private static final String QUOTA_PREFIX = "quota:";
    /** 用户/配额管理动作的审计归属空间：统一记公共空间，避免散落在各用户个人空间难以检索。 */
    private static final String DEFAULT_AUDIT_LIBRARY = org.example.library.KbLibrary.DEFAULT_LIBRARY;
    private static final String METRIC_RETRIEVAL = "retrieval";
    private static final String METRIC_CHAT = "chat";

    /** 限额覆盖清除哨兵：-1 表示移除用户级覆盖、跟随全局默认（0 本身是合法值=不限制）。 */
    private static final long CLEAR_LIMIT = -1L;

    @Value("${app.admin.usernames:}")
    private String adminUsernames;

    private final UserRepository userRepository;
    private final KnowledgeDocumentRepository documentRepository;
    private final QuotaDailyCounter quotaCounter;
    private final RuntimeConfigStore runtimeConfig;
    private final AuditLogService auditLogService;
    private final PasswordEncoder passwordEncoder;
    /** 账号状态/角色变更后失效 JwtAuthFilter 的身份微缓存，使变更立即生效（见 evictIdentity）。 */
    private final org.example.security.JwtAuthFilter jwtAuthFilter;

    public AdminUserServiceImpl(UserRepository userRepository,
                                KnowledgeDocumentRepository documentRepository,
                                QuotaDailyCounter quotaCounter,
                                RuntimeConfigStore runtimeConfig,
                                AuditLogService auditLogService,
                                PasswordEncoder passwordEncoder,
                                org.example.security.JwtAuthFilter jwtAuthFilter) {
        this.userRepository = userRepository;
        this.documentRepository = documentRepository;
        this.quotaCounter = quotaCounter;
        this.runtimeConfig = runtimeConfig;
        this.auditLogService = auditLogService;
        this.passwordEncoder = passwordEncoder;
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Override
    public Page<UserView> list(String keyword, Pageable page) {
        return userRepository.findAll(keywordSpec(keyword), page).map(this::toView);
    }

    @Override
    @Transactional
    public UserView updateRole(Long id, String role, String operator) {
        if (!ROLE_USER.equals(role) && !ROLE_ADMIN.equals(role)) {
            throw new IllegalArgumentException("非法角色: " + role);
        }
        UserEntity user = loadUser(id);
        if (isBootstrapAdmin(user.getUsername()) && !ROLE_ADMIN.equals(role)) {
            throw new IllegalArgumentException("引导管理员不允许降级角色");
        }
        if (user.getUsername().equals(operator) && !ROLE_ADMIN.equals(role)) {
            throw new IllegalArgumentException("不能降级自己的角色");
        }
        if (!ROLE_ADMIN.equals(user.getRole()) && ROLE_ADMIN.equals(role)) {
            // 新管理员需重新登录获取含 ADMIN 角色的 token（鉴权层身份缓存已同步失效，无需等待 TTL）
            log.info("用户已提升为管理员 - username: {}, operator: {}", user.getUsername(), operator);
        }
        user.setRole(role);
        userRepository.save(user);
        jwtAuthFilter.evictIdentity(user.getUsername());
        auditLogService.record(DEFAULT_AUDIT_LIBRARY, operator, "USER_ROLE",
                "USER", id, "角色变更为 " + role + "：" + user.getUsername());
        return toView(user);
    }

    @Override
    @Transactional
    public UserView updateStatus(Long id, String status, String operator) {
        if (!STATUS_ACTIVE.equals(status) && !STATUS_DISABLED.equals(status)) {
            throw new IllegalArgumentException("非法状态: " + status);
        }
        UserEntity user = loadUser(id);
        if (STATUS_DISABLED.equals(status)) {
            if (user.getUsername().equals(operator)) {
                throw new IllegalArgumentException("不能禁用自己的账号");
            }
            if (isBootstrapAdmin(user.getUsername())) {
                throw new IllegalArgumentException("引导管理员不允许禁用");
            }
        }
        user.setStatus(status);
        userRepository.save(user);
        // 禁用/启用立即生效：清掉鉴权层的身份微缓存，被禁用账号的既有 token 当场失效
        jwtAuthFilter.evictIdentity(user.getUsername());
        auditLogService.record(DEFAULT_AUDIT_LIBRARY, operator, "USER_STATUS",
                "USER", id, ("DISABLED".equals(status) ? "禁用" : "启用") + "账号：" + user.getUsername());
        return toView(user);
    }

    @Override
    @Transactional
    public void resetPassword(Long id, String newPassword, String operator) {
        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("新密码长度至少 8 位");
        }
        UserEntity user = loadUser(id);
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        auditLogService.record(DEFAULT_AUDIT_LIBRARY, operator, "USER_RESET_PWD",
                "USER", id, "重置密码：" + user.getUsername());
    }

    @Override
    public Page<UserQuotaView> quotaPage(String keyword, Pageable page) {
        return userRepository.findAll(keywordSpec(keyword), page).map(this::toQuotaView);
    }

    @Override
    @Transactional
    public UserQuotaView updateLimits(Long id, Map<String, Object> body, String operator) {
        if (body == null) {
            throw new IllegalArgumentException("缺少限额参数");
        }
        UserEntity user = loadUser(id);
        List<String> changes = new ArrayList<>();
        applyLimit(body, "docLimit", "文档", changes, user::setDocLimit, user::getDocLimit);
        applyLimit(body, "retrievalLimit", "检索", changes, user::setRetrievalLimit, user::getRetrievalLimit);
        applyLimit(body, "chatLimit", "问答", changes, user::setChatLimit, user::getChatLimit);
        if (changes.isEmpty()) {
            throw new IllegalArgumentException("未提供任何限额变更");
        }
        userRepository.save(user);
        auditLogService.record(DEFAULT_AUDIT_LIBRARY, operator, "UPDATE_QUOTA",
                "USER", id, "调整个人库限额 - " + user.getUsername() + "：" + String.join("，", changes));
        return toQuotaView(user);
    }

    /** 解析单个限额字段：key 不存在=不变更；-1=清除覆盖；>=0=设置。 */
    private void applyLimit(Map<String, Object> body, String key, String label, List<String> changes,
                            java.util.function.Consumer<Long> setter,
                            java.util.function.Supplier<Long> getter) {
        if (!body.containsKey(key)) {
            return;
        }
        Object raw = body.get(key);
        if (raw == null) {
            throw new IllegalArgumentException(label + "限额值非法");
        }
        long value;
        try {
            value = ((Number) raw).longValue();
        } catch (ClassCastException e) {
            throw new IllegalArgumentException(label + "限额值非法");
        }
        if (value == CLEAR_LIMIT) {
            if (getter.get() != null) {
                setter.accept(null);
                changes.add(label + "恢复默认");
            }
            return;
        }
        if (value < 0) {
            throw new IllegalArgumentException(label + "限额值非法（负数，清除覆盖请用 -1）");
        }
        setter.accept(value);
        changes.add(label + "=" + (value == 0 ? "不限制" : value));
    }

    private UserEntity loadUser(Long id) {
        return userRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
    }

    /** 引导管理员（app.admin.usernames）：角色/状态受保护，防止误操作导致失去管理入口。 */
    private boolean isBootstrapAdmin(String username) {
        if (adminUsernames == null || adminUsernames.isBlank()) {
            return false;
        }
        for (String name : adminUsernames.split(",")) {
            if (name.trim().equals(username)) {
                return true;
            }
        }
        return false;
    }

    /** keyword 模糊匹配用户名或昵称（均小写包含）。 */
    private Specification<UserEntity> keywordSpec(String keyword) {
        return (root, query, cb) -> {
            if (keyword == null || keyword.isBlank()) {
                return cb.conjunction();
            }
            String like = "%" + keyword.trim().toLowerCase() + "%";
            return cb.or(
                    cb.like(cb.lower(root.get("username")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("nickname"), "")), like));
        };
    }

    private UserView toView(UserEntity u) {
        return UserView.builder()
                .id(u.getId())
                .username(u.getUsername())
                .nickname(u.getNickname())
                .role(u.getRole())
                .status(u.getStatus() == null ? STATUS_ACTIVE : u.getStatus())
                .libraryId(u.getLibraryId())
                .docLimit(u.getDocLimit())
                .retrievalLimit(u.getRetrievalLimit())
                .chatLimit(u.getChatLimit())
                .createdDate(u.getCreatedDate())
                .build();
    }

    /** 组装个人库配额视图：用量取实时数据，限额取覆盖值或全局默认。 */
    private UserQuotaView toQuotaView(UserEntity u) {
        String library = KbLibrary.personalCode(u.getUsername());
        boolean active = !"DISABLED".equals(u.getStatus());
        return UserQuotaView.builder()
                .userId(u.getId())
                .username(u.getUsername())
                .nickname(u.getNickname())
                .status(active ? STATUS_ACTIVE : STATUS_DISABLED)
                .docUsed(documentRepository.countByLibraryId(library))
                .docLimit(limitOf(u.getDocLimit(), runtimeConfig.getLong("quota.user-doc-limit")))
                .docLimitCustom(u.getDocLimit() != null)
                .retrievalUsed(quotaCounter.peek(QuotaDailyCounter.dayKey(QUOTA_PREFIX, library, METRIC_RETRIEVAL)))
                .retrievalLimit(limitOf(u.getRetrievalLimit(), runtimeConfig.getLong("quota.user-retrieval-limit")))
                .retrievalLimitCustom(u.getRetrievalLimit() != null)
                .chatUsed(quotaCounter.peek(QuotaDailyCounter.dayKey(QUOTA_PREFIX, library, METRIC_CHAT)))
                .chatLimit(limitOf(u.getChatLimit(), runtimeConfig.getLong("quota.user-chat-limit")))
                .chatLimitCustom(u.getChatLimit() != null)
                .build();
    }

    private long limitOf(Long override, long configured) {
        return override != null ? override : configured;
    }
}
