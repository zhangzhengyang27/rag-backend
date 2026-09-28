package org.example.aiservice;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;
import reactor.core.publisher.Flux;

/**
 * 知识库问答助手（流式输出）。
 */
@AiService(wiringMode = AiServiceWiringMode.EXPLICIT, streamingChatModel = "openAiStreamingChatModel",
        tools = {"chatHistoryTools", "kbSearchTools", "retrievalVerifyTools", "kbLibraryTools"})
public interface KbAssistant {

    @SystemMessage(fromResource = "/kbSystem.txt")
    @UserMessage("""
            当前sessionId:{{sessionId}};当前对话ID:{{conversationId}};
            与本对话相关的历史上下文（含此前用户问题与助手回答）:
            {{history}}
            当前用户问题:{{message}}
            系统已为你预检索到的知识库证据（作为首轮检索结果直接使用，无需再重复调用 firstSearch）:
            {{sources}}
            """)
    Flux<String> answer(@V("sessionId") String sessionId,
                        @V("conversationId") String conversationId,
                        @V("history") String history,
                        @V("message") String message,
                        @V("sources") String sources);
}
