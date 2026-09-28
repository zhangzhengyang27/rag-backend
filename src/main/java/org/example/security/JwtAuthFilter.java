package org.example.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JWT 认证过滤器：解析 Authorization: Bearer <token>，
 * 并以数据库中的账号状态/角色为准注入 Spring Security 上下文。
 * <p>角色与禁用状态以 DB 为准（而非 token 旧值），使「改角色 / 禁用账号」即时生效，
 * 无需等待 token 自然过期；按用户做 30s 微缓存以摊薄每次请求的账号查询开销。
 * 被禁用（或已删除）的用户不注入认证，由 SecurityConfig 入口点统一返回 401。</p>
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    /** 账号状态/角色解析结果的缓存 TTL（毫秒）：禁用与改角色的最大生效延迟。 */
    private static final long IDENTITY_CACHE_TTL_MS = 30_000L;

    /** DB 解析后的账号身份快照：role/libraryId 以库为准；disabled 含"账号已删除"。 */
    private record IdentitySnapshot(String role, String libraryId, boolean disabled, long expireAt) {
        boolean expired() {
            return System.currentTimeMillis() >= expireAt;
        }
    }

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private UserRepository userRepository;

    private final ConcurrentHashMap<String, IdentitySnapshot> identityCache = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = header.substring(BEARER_PREFIX.length());
            String[] identity = jwtUtils.parseIdentity(token);
            if (identity != null) {
                IdentitySnapshot snapshot = resolve(identity);
                if (!snapshot.disabled()) {
                    List<SimpleGrantedAuthority> authorities =
                            List.of(new SimpleGrantedAuthority("ROLE_" + snapshot.role()));
                    AuthenticatedUser principal =
                            new AuthenticatedUser(identity[0], snapshot.role(), snapshot.libraryId());
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(principal, null, authorities);
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
        }
        filterChain.doFilter(request, response);
    }

    /** 按用户名解析账号现状（角色/空间/禁用），带 TTL 微缓存；账号不存在视为禁用。 */
    private IdentitySnapshot resolve(String[] identity) {
        String username = identity[0];
        IdentitySnapshot cached = identityCache.get(username);
        if (cached != null && !cached.expired()) {
            return cached;
        }
        // 过期条目先移除：防止长期运行下历史用户名（含已删除账号）的条目无界堆积
        if (cached != null) {
            identityCache.remove(username, cached);
        }
        IdentitySnapshot resolved = userRepository.findByUsername(username)
                .map(u -> new IdentitySnapshot(
                        u.getRole() == null ? identity[1] : u.getRole(),
                        (u.getLibraryId() == null || u.getLibraryId().isBlank()) ? identity[2] : u.getLibraryId(),
                        "DISABLED".equals(u.getStatus()),
                        System.currentTimeMillis() + IDENTITY_CACHE_TTL_MS))
                .orElse(new IdentitySnapshot(identity[1], identity[2], true,
                        System.currentTimeMillis() + IDENTITY_CACHE_TTL_MS));
        identityCache.put(username, resolved);
        return resolved;
    }

    /**
     * 管理端变更账号（禁用/启用/改角色）后主动失效该用户的身份缓存，
     * 使变更立即生效而无需等待 30s TTL；否则被禁用账号的既有 token 仍可用至缓存过期。
     */
    public void evictIdentity(String username) {
        if (username != null && !username.isBlank()) {
            identityCache.remove(username);
        }
    }
}
