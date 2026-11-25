package com.techcodex.service;

import com.techcodex.dto.AuthRequest;
import com.techcodex.dto.AuthResponse;

/**
 * 用户认证服务：注册与登录。
 */
public interface AuthService {

    AuthResponse register(AuthRequest request);

    AuthResponse login(AuthRequest request);
}
