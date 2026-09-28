package org.example.security;

import jakarta.servlet.FilterChain;
import org.example.entity.UserEntity;
import org.example.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

/**
 * JWT 过滤器单测：账号状态以 DB 为准——被禁用用户的仍有效 token 不注入认证（请求被入口点拒为 401），
 * 正常用户照常注入且角色/空间取库内最新值（角色变更即时生效）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JwtAuthFilterTest {

    @Mock
    private JwtUtils jwtUtils;

    @Mock
    private UserRepository userRepository;

    private JwtAuthFilter filter;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @BeforeEach
    void setUp() {
        filter = new JwtAuthFilter();
        org.springframework.test.util.ReflectionTestUtils.setField(filter, "jwtUtils", jwtUtils);
        org.springframework.test.util.ReflectionTestUtils.setField(filter, "userRepository", userRepository);
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest request(String username, String role, String libraryId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token");
        lenient().when(jwtUtils.parseIdentity("token")).thenReturn(new String[]{username, role, libraryId});
        return request;
    }

    @Test
    void disabledUserDoesNotGetAuthentication() throws Exception {
        UserEntity disabled = new UserEntity();
        disabled.setUsername("dave");
        disabled.setRole("USER");
        disabled.setStatus("DISABLED");
        when(userRepository.findByUsername("dave")).thenReturn(Optional.of(disabled));

        MockHttpServletRequest request = request("dave", "USER", "u-dave");
        FilterChain chain = mock(FilterChain.class);
        filter.doFilterInternal(request, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void deletedUserDoesNotGetAuthentication() throws Exception {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        MockHttpServletRequest request = request("ghost", "ADMIN", "default");
        filter.doFilterInternal(request, new MockHttpServletResponse(), mock(FilterChain.class));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void activeUserGetsFreshRoleAndLibraryFromDb() throws Exception {
        UserEntity promoted = new UserEntity();
        promoted.setUsername("carol");
        promoted.setRole("ADMIN");
        promoted.setLibraryId("u-carol");
        promoted.setStatus("ACTIVE");
        when(userRepository.findByUsername("carol")).thenReturn(Optional.of(promoted));

        // token 是旧的 USER 角色：鉴权层应按 DB 最新角色注入（角色变更即时生效）
        MockHttpServletRequest request = request("carol", "USER", "u-carol");
        FilterChain chain = mock(FilterChain.class);
        filter.doFilterInternal(request, new MockHttpServletResponse(), chain);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
        assertThat(((AuthenticatedUser) auth.getPrincipal()).libraryId()).isEqualTo("u-carol");
    }
}
