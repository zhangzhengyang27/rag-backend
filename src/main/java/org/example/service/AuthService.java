package org.example.service;

import org.example.dto.AuthRequest;
import org.example.dto.AuthResponse;

/**
 * 用户认证服务：注册与登录。
 */
public interface AuthService {

    AuthResponse register(AuthRequest request);

    AuthResponse login(AuthRequest request);
}
