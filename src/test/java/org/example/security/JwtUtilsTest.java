package org.example.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JWT 工具单测：token 携带空间声明、空空间回落默认空间。
 */
class JwtUtilsTest {

    private JwtUtils jwtUtils;

    @BeforeEach
    void setUp() {
        jwtUtils = new JwtUtils();
        ReflectionTestUtils.setField(jwtUtils, "secret",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        ReflectionTestUtils.setField(jwtUtils, "expireDays", 7L);
        jwtUtils.init();
    }

    @Test
    void tokenCarriesLibraryId() {
        String token = jwtUtils.generateToken("u1", "USER", "acme");
        String[] identity = jwtUtils.parseIdentity(token);
        assertThat(identity).isNotNull();
        assertThat(identity[0]).isEqualTo("u1");
        assertThat(identity[1]).isEqualTo("USER");
        assertThat(identity[2]).isEqualTo("acme");
    }

    @Test
    void tokenDefaultsLibraryWhenNull() {
        String token = jwtUtils.generateToken("u2", "USER", null);
        assertThat(jwtUtils.parseIdentity(token)[2]).isEqualTo("default");
    }
}
