package org.example.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.example.bean.ChatHistory;
import org.example.entity.ChatHistoryEntity;
import org.example.repository.ChatHistoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
@Slf4j
public class ChatHistoryTools {

    @Autowired
    private ChatHistoryRepository chatHistoryRepository;

    @Tool("通过sessionId返回用户的对话历史")
    public List<ChatHistory> getChatHistory(@P("用户的sessionId") String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Collections.emptyList();
        }
        // 越权防护（默认拒绝）：sessionId 由模型自行填入，必须严格等于当前登录用户，
        // 防止被 prompt injection 诱导传入他人 sessionId 读取他人对话历史。
        // 注意：安全上下文不总是可得的（流式问答在 servlet 过滤器清空 ThreadLocal 之后才执行工具），
        // 此时宁可拒绝返回空，也绝不放行未知身份，避免默认放行造成越权读取。
        String loginUser = currentLoginUser();
        if (loginUser == null || !loginUser.equals(sessionId)) {
            log.warn("拦截对他人对话历史的访问 - requested: {}, currentLoginUser: {}", sessionId, loginUser);
            return Collections.emptyList();
        }
        // 先取最新的20条（倒序），再反转为时间升序，保证喂给大模型的上下文时间线正确
        List<ChatHistoryEntity> entityList = chatHistoryRepository.findTop20BySessionIdOrderByIdDesc(sessionId);
        Collections.reverse(entityList);
        List<ChatHistory> result = new ArrayList<>(entityList.size());
        for (ChatHistoryEntity entity : entityList) {
            ChatHistory chatHistory = new ChatHistory();
            chatHistory.setRole(entity.getRole());
            chatHistory.setContent(entity.getContent());
            result.add(chatHistory);
        }
        log.info("getChatHistory 返回 {} 条记录 - sessionId: {}", result.size(), sessionId);
        return result;
    }

    /** 当前登录用户名；无认证上下文时返回 null（调用方必须拒绝，不能默认放行）。 */
    private String currentLoginUser() {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        // principal 是 JwtAuthFilter 注入的 AuthenticatedUser（record），不是 String——
        // 这里必须按实际类型取 username，否则恒为 null，工具永远拒绝。
        if (auth != null && auth.isAuthenticated()
                && auth.getPrincipal() instanceof org.example.security.AuthenticatedUser user) {
            return user.username();
        }
        return null;
    }
}
