package org.example.service.impl;

import org.example.dto.AuthRequest;
import org.example.dto.AuthResponse;
import org.example.entity.UserEntity;
import org.example.repository.UserRepository;
import org.example.security.InMemoryLoginGuard;
import org.example.security.JwtUtils;
import org.example.security.LoginGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 认证服务单测：注册把用户默认空间指向个人知识库，登录签发带空间标识 token，
 * 防爆破锁定生效。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtUtils jwtUtils;
    @Spy
    private LoginGuard loginGuard = new InMemoryLoginGuard(3, 600);

    @InjectMocks
    private AuthServiceImpl authService;

    @Test
    void registerPointsUserToPersonalSpace() {
        ReflectionTestUtils.setField(authService, "publicLibraryCode", "default");
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        when(passwordEncoder.encode("secret123")).thenReturn("enc");
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> {
            UserEntity u = inv.getArgument(0);
            u.setId(1L);
            return u;
        });
        when(jwtUtils.generateToken(anyString(), anyString(), anyString())).thenReturn("tok");

        AuthRequest req = new AuthRequest();
        req.setUsername("alice");
        req.setPassword("secret123");
        req.setNickname("Alice");

        AuthResponse res = authService.register(req);

        // 注册即把默认空间指向个人知识库（u-{username}），token 同步指向它
        assertThat(res.getLibraryId()).isEqualTo("u-alice");
        org.mockito.ArgumentCaptor<UserEntity> cap = org.mockito.ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(cap.capture());
        assertThat(cap.getValue().getLibraryId()).isEqualTo("u-alice");
    }

    @Test
    void registerRejectsShortPassword() {
        ReflectionTestUtils.setField(authService, "publicLibraryCode", "default");
        AuthRequest req = new AuthRequest();
        req.setUsername("carol");
        req.setPassword("secret1");

        // 注册密码策略：服务层强制至少 8 位（登录不受影响，存量短密码用户仍可登录）
        assertThatThrownBy(() -> authService.register(req)).hasMessageContaining("至少 8 位");
        verify(userRepository, org.mockito.Mockito.never()).save(any(UserEntity.class));
    }



    @Test
    void loginIssuesTokenWithLibrary() {
        UserEntity u = new UserEntity();
        u.setUsername("bob");
        u.setPassword("enc");
        u.setRole("USER");
        u.setLibraryId("acme");
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("secret1", "enc")).thenReturn(true);
        when(jwtUtils.generateToken("bob", "USER", "acme")).thenReturn("tok");

        AuthRequest req = new AuthRequest();
        req.setUsername("bob");
        req.setPassword("secret1");

        AuthResponse res = authService.login(req);

        assertThat(res.getLibraryId()).isEqualTo("acme");
    }

    @Test
    void loginLockedAfterTooManyFailures() {
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        UserEntity bob = new UserEntity();
        bob.setPassword("enc");
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(bob));
        AuthRequest req = new AuthRequest();
        req.setUsername("bob");
        req.setPassword("wrong");

        // 阈值 3：前两次失败正常返回错误，第三次失败后账号进入锁定
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> authService.login(req)).hasMessageContaining("用户名或密码错误");
        }
        assertThatThrownBy(() -> authService.login(req)).hasMessageContaining("用户名或密码错误");

        AuthRequest anyAttempt = new AuthRequest();
        anyAttempt.setUsername("bob");
        anyAttempt.setPassword("secret1");
        // 锁定后即使密码正确也拒绝（先于密码校验：不再新增用户查询）
        assertThatThrownBy(() -> authService.login(anyAttempt))
                .hasMessageContaining("临时锁定");
        verify(userRepository, org.mockito.Mockito.times(3)).findByUsername("bob");
    }
}
