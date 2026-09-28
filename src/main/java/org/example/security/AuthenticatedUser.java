package org.example.security;

/** JWT 解析出的认证主体：用户名 + 平台角色 + 当前空间。 */
public record AuthenticatedUser(String username, String role, String libraryId) {
}
