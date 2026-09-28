package org.example.library;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索范围解析单测：public/mine/all 三态到空间集合的映射。
 * mine/all 以按用户名派生的个人空间为准（与 token 指向解耦）；未知取值按 public 兜底。
 */
class KbScopesTest {

    @Test
    void publicOrNullResolvesToDefaultOnly() {
        assertThat(KbScopes.resolve("public", "alice", "u-alice", "default")).containsExactly("default");
        assertThat(KbScopes.resolve(null, "alice", "u-alice", "default")).containsExactly("default");
        assertThat(KbScopes.resolve("", "alice", "u-alice", "default")).containsExactly("default");
        assertThat(KbScopes.resolve("unknown", "alice", "u-alice", "default")).containsExactly("default");
    }

    @Test
    void mineResolvesToPersonalLibraryByUsername() {
        // 关键语义：即使 token 仍指 default（存量用户），mine 也定位到个人空间
        assertThat(KbScopes.resolve("mine", "alice", "default", "default")).containsExactly("u-alice");
        assertThat(KbScopes.resolve("MINE", "bob", "default", "default")).containsExactly("u-bob");
    }

    @Test
    void mineFallsBackWhenUsernameBlank() {
        // 无登录态的服务级调用回落 fallback 空间
        assertThat(KbScopes.resolve("mine", null, "default", "default")).containsExactly("default");
        assertThat(KbScopes.resolve("mine", "  ", "acme", "default")).containsExactly("acme");
    }

    @Test
    void allUnionsDefaultAndPersonal() {
        assertThat(KbScopes.resolve("all", "alice", "default", "default"))
                .containsExactly("default", "u-alice");
        assertThat(KbScopes.resolve("ALL", "bob", "default", "default"))
                .containsExactly("default", "u-bob");
    }

    @Test
    void allDeduplicatesWhenUsernameBlankAndFallbackIsDefault() {
        List<String> librarys = KbScopes.resolve("all", null, "default", "default");
        assertThat(librarys).containsExactly("default");
    }
}
