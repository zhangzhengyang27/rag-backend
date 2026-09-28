package org.example.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 登录/注册响应：返回 JWT 令牌、用户基本信息与角色。
 */
@Data
@AllArgsConstructor
public class AuthResponse {

    private String token;

    private String tokenType;

    private String username;

    private String nickname;

    private String role;

    private String libraryId;

    public static AuthResponse of(String token, String username, String nickname, String role, String libraryId) {
        return new AuthResponse(token, "Bearer", username, nickname, role, libraryId);
    }
}
