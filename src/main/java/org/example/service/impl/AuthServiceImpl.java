package org.example.service.impl;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.example.dto.AuthRequest;
import org.example.dto.AuthResponse;
import org.example.entity.UserEntity;
import org.example.repository.UserRepository;
import org.example.security.JwtUtils;
import org.example.service.AuthService;
import org.example.library.KbLibrary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证服务实现：注册（BCrypt 加密）、登录（校验 + 签发 JWT）。
 * 启动时根据 {@code app.admin.usernames} 配置将指定账号提升为管理员。
 */
@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    private static final String ROLE_USER = "USER";
    private static final String ROLE_ADMIN = "ADMIN";

    @Value("${app.admin.usernames:}")
    private String adminUsernames;

    @Value("${app.kb.library.public-code:default}")
    private String publicLibraryCode;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private org.example.security.LoginGuard loginGuard;

    @Override
    @Transactional
    public AuthResponse register(AuthRequest request) {
        String username = request.getUsername().trim();
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("用户名已存在");
        }
        // 注册密码策略（至少 8 位）：放在服务层而非 DTO，登录请求复用同一 DTO 时不受影响，
        // 存量 6-7 位密码的老用户不会被 DTO 校验挡在登录之外
        if (request.getPassword() == null || request.getPassword().length() < 8) {
            throw new IllegalArgumentException("密码长度至少 8 位");
        }
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(request.getNickname());
        user.setRole(ROLE_USER);
        // 私有知识库引导：注册即把用户默认空间指向其个人知识库（u-{username}），token 同步指向它。
        // 个人空间无需预建任何行——文档/配额/检索均按空间标识即时生效。
        String personalCode = KbLibrary.personalCode(username);
        user.setLibraryId(personalCode);
        userRepository.save(user);
        return AuthResponse.of(jwtUtils.generateToken(username, ROLE_USER, personalCode),
                username, user.getNickname(), ROLE_USER, personalCode);
    }

    @Override
    public AuthResponse login(AuthRequest request) {
        String username = request.getUsername().trim();
        // 防爆破：失败次数达阈值的账号在锁定窗口内直接拒绝（先于密码校验，避免给爆破者计时反馈）
        if (loginGuard.isLocked(username)) {
            throw new IllegalArgumentException("登录失败次数过多，账号已临时锁定，请稍后再试");
        }
        UserEntity user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            loginGuard.recordFailure(username);
            throw new IllegalArgumentException("用户名或密码错误");
        }
        if ("DISABLED".equals(user.getStatus())) {
            throw new IllegalArgumentException("账号已被禁用，请联系管理员");
        }
        loginGuard.recordSuccess(username);
        String role = user.getRole() == null ? ROLE_USER : user.getRole();
        String libraryId = user.getLibraryId() == null || user.getLibraryId().isBlank()
                ? publicLibraryCode : user.getLibraryId();
        return AuthResponse.of(jwtUtils.generateToken(username, role, libraryId), username,
                user.getNickname(), role, libraryId);
    }

    /**
     * 启动时将 {@code app.admin.usernames}（逗号分隔）中的账号提升为管理员，
     * 支持将存量用户升级，避免明文存储管理员密码。
     */
    @PostConstruct
    @Transactional
    public void bootstrapAdmins() {
        if (adminUsernames == null || adminUsernames.isBlank()) {
            return;
        }
        for (String name : adminUsernames.split(",")) {
            String username = name.trim();
            if (username.isEmpty()) {
                continue;
            }
            userRepository.findByUsername(username).ifPresent(user -> {
                if (!ROLE_ADMIN.equals(user.getRole())) {
                    user.setRole(ROLE_ADMIN);
                    userRepository.save(user);
                    log.info("账号已提升为管理员 - username: {}", username);
                }
            });
        }
    }
}
