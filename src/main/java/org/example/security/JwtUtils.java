package org.example.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 生成与解析工具。
 * 密钥通过 {@code app.jwt.secret} 注入（来源建议为环境变量 JWT_SECRET）。
 * 出于安全考虑，不再提供硬编码的开发默认密钥——未配置密钥时应用直接拒绝启动，
 * 避免攻击者用公开的固定密钥伪造任意角色（含 ADMIN）的 token。
 * Token 携带 username（subject）与 role（角色）两个声明，供权限控制使用。
 */
@Component
public class JwtUtils {

    /** 默认角色 */
    private static final String DEFAULT_ROLE = "USER";

    /** 默认空间（token 未携带 libraryId 时回落） */
    private static final String DEFAULT_LIBRARY = "default";

    @Value("${app.jwt.secret:}")
    private String secret;

    @Value("${app.jwt.expire-days:7}")
    private long expireDays;

    private SecretKey key;

    @PostConstruct
    public void init() {
        if (secret == null || secret.isBlank()) {
            // HS256 要求密钥至少 32 字节；缺失即拒绝启动，禁止回落固定密钥。
            throw new IllegalStateException(
                    "app.jwt.secret 未配置。请通过环境变量 JWT_SECRET 注入 JWT 签名密钥（HS256，至少 32 字节）。"
                            + "生成示例：openssl rand -base64 48");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(String username, String role, String libraryId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expireDays * 24 * 60 * 60 * 1000L);
        return Jwts.builder()
                .subject(username)
                .claim("role", (role == null || role.isBlank()) ? DEFAULT_ROLE : role)
                .claim("libraryId", (libraryId == null || libraryId.isBlank()) ? DEFAULT_LIBRARY : libraryId)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    /**
     * 解析 token 并返回用户名；token 无效或过期时返回 null。
     */
    public String parseUsername(String token) {
        String[] identity = parseIdentity(token);
        return identity == null ? null : identity[0];
    }

    /**
     * 解析 token 并返回角色；token 无效或过期时返回 null。
     */
    public String parseRole(String token) {
        String[] identity = parseIdentity(token);
        return identity == null ? null : identity[1];
    }

    /**
     * 解析 token 并返回所属知识库空间；token 无效或过期时返回 null。
     */
    public String parseLibrary(String token) {
        String[] identity = parseIdentity(token);
        return identity == null ? null : identity[2];
    }

    /**
     * 一次性解析 token，返回 [用户名, 角色, 知识库空间]；无效或过期时返回 null。
     * 兼容旧 claim：空间重命名前签发的 token 携带 libraryId（7 天有效期内），解析时优先
     * 读 libraryId、回落 libraryId，避免存量登录态在窗口期内失效。
     */
    public String[] parseIdentity(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Object role = claims.get("role");
            Object libraryId = claims.get("libraryId");
            if (libraryId == null) {
                libraryId = claims.get("libraryId"); // 旧 token 兼容
            }
            return new String[]{
                    claims.getSubject(),
                    role == null ? DEFAULT_ROLE : String.valueOf(role),
                    libraryId == null ? DEFAULT_LIBRARY : String.valueOf(libraryId)
            };
        } catch (Exception e) {
            return null;
        }
    }
}
