package org.example.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/** 从 SecurityContext 读取当前登录主体与空间。无登录态时返回空。 */
public final class SecurityUtil {

    private SecurityUtil() {
    }

    public static Optional<AuthenticatedUser> currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser u) {
            return Optional.of(u);
        }
        return Optional.empty();
    }

    public static String currentUsername() {
        return currentUser().map(AuthenticatedUser::username).orElse(null);
    }

    /** 当前空间；无登录态或空间为空时返回 fallback（如默认空间）。 */
    public static String currentLibraryId(String fallback) {
        return currentUser()
                .map(AuthenticatedUser::libraryId)
                .filter(s -> s != null && !s.isBlank())
                .orElse(fallback);
    }
}
