package org.example.tools;

import org.example.bean.ChatHistory;
import org.example.entity.ChatHistoryEntity;
import org.example.repository.ChatHistoryRepository;
import org.example.security.AuthenticatedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对话历史工具单测：principal 类型修复后，登录用户可查本人历史；
 * 无登录态（流式工具线程）或 sessionId 不匹配（疑似 prompt injection）时默认拒绝。
 */
@ExtendWith(MockitoExtension.class)
class ChatHistoryToolsTest {

    @Mock
    private ChatHistoryRepository chatHistoryRepository;

    @InjectMocks
    private ChatHistoryTools tools;

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(username, "USER", "default"), null, List.of()));
    }

    @Test
    void returnsHistoryWhenSessionIdMatchesLoginUser() {
        loginAs("alice");
        ChatHistoryEntity entity = new ChatHistoryEntity();
        entity.setRole("user");
        entity.setContent("你好");
        when(chatHistoryRepository.findTop20BySessionIdOrderByIdDesc("alice")).thenReturn(List.of(entity));

        List<ChatHistory> result = tools.getChatHistory("alice");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getContent()).isEqualTo("你好");
    }

    @Test
    void deniesSessionIdOfAnotherUser() {
        loginAs("alice");

        List<ChatHistory> result = tools.getChatHistory("bob");

        assertThat(result).isEmpty();
        verify(chatHistoryRepository, never()).findTop20BySessionIdOrderByIdDesc("bob");
    }

    @Test
    void deniesWhenNoAuthContext() {
        // 流式问答在无登录态线程执行工具：必须拒绝，不能默认放行
        List<ChatHistory> result = tools.getChatHistory("alice");

        assertThat(result).isEmpty();
        verify(chatHistoryRepository, never()).findTop20BySessionIdOrderByIdDesc("alice");
    }
}
