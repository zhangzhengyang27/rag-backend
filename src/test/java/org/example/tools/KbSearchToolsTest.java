package org.example.tools;

import org.example.config.KbConfig;
import org.example.security.AuthenticatedUser;
import org.example.service.KbSearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 检索工具单测：无登录态（LLM 流式工具线程的常态）时 fail-closed 拒绝检索，
 * 防止静默回落默认空间造成跨空间泄露；有登录态时正常检索。
 */
@ExtendWith(MockitoExtension.class)
class KbSearchToolsTest {

    @Mock
    private KbSearchService kbSearchService;

    @Mock
    private KbConfig kbConfig;

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    private KbSearchTools newTools() {
        return new KbSearchTools(kbSearchService, kbConfig);
    }

    private void loginAs(String username, String libraryId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(username, "USER", libraryId), null, List.of()));
    }

    @Test
    void firstSearchFailsClosedWithoutAuthContext() {
        String out = newTools().firstSearch("某个知识点");
        assertThat(out).contains("检索上下文不可用");
        verifyNoInteractions(kbSearchService);
    }

    @Test
    void refineSearchFailsClosedWithoutAuthContext() {
        String out = newTools().refineSearch("某个缺失概念");
        assertThat(out).contains("检索上下文不可用");
        verifyNoInteractions(kbSearchService);
    }

    @Test
    void firstSearchWorksWithAuthContext() {
        loginAs("alice", "acme");
        when(kbConfig.getRetrievalMaxResults()).thenReturn(4);
        when(kbSearchService.retrieveForLlm("某个知识点", 4)).thenReturn("片段内容");

        String out = newTools().firstSearch("某个知识点");

        assertThat(out).isEqualTo("片段内容");
    }
}
