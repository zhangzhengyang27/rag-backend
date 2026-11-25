package com.techcodex.service;

import com.techcodex.vo.ChatHistoryVo;
import com.techcodex.vo.ConversationVo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 知识库问答服务：基于检索增强生成，以 SSE 流式输出为用户解答知识库相关问题。
 * 支持多对话（每个用户可拥有多个独立对话）与多轮上下文。
 */
public interface KbChatService {

    /**
     * 基于用户消息流式返回问答结果（SSE 事件流）。
     * conversationId 为空时新建对话并生成 ID（事件流首帧以 conversation 事件返回）。
     */
    Flux<ServerSentEvent<String>> chatStream(String userId, String conversationId, String message);

    /**
     * 文档内问答（"问这篇文档"）：检索范围锁定到 docPath 指向的单篇文档（filePath 前缀匹配）。
     *
     * @param docPath 文档相对路径（docs 根相对，如 "04-前端/03-JavaScript/09-网络请求/02-Fetch API.md"），null 等价全局问答
     */
    Flux<ServerSentEvent<String>> chatStream(String userId, String conversationId, String message, String docPath);

    /**
     * 带 scope 的问答：public（缺省，仅公共文档）/ mine（当前空间）/ all（公共+当前空间）。
     * docPath 非空时范围锁定到公共文档，scope 失效。
     */
    Flux<ServerSentEvent<String>> chatStream(String userId, String conversationId, String message, String docPath, String scope);

    /**
     * 带 scope + 课程分类过滤的问答：category 非空时仅在 kb_document.category 精确匹配的文档内检索。
     * 优先级：docPath（单文档锁定）&gt; category；docPath 非空时 category 失效。
     */
    Flux<ServerSentEvent<String>> chatStream(String userId, String conversationId, String message,
                                             String docPath, String scope, String category);

    /**
     * 带「重新生成」语义的问答：regenerate=true 时，在生成新回答前先删除该对话的最后一轮问答
     * （最后一条助手回答及其对应的用户提问），避免同一问题在历史里重复落库。
     * 其余参数含义与六参版本一致；默认实现忽略 regenerate，保持向后兼容。
     */
    default Flux<ServerSentEvent<String>> chatStream(String userId, String conversationId, String message,
                                                     String docPath, String scope, String category,
                                                     boolean regenerate) {
        return chatStream(userId, conversationId, message, docPath, scope, category);
    }

    /** 分页查询指定对话的问答历史；conversationId 为空时回退为查询全部（旧行为）。 */
    Page<ChatHistoryVo> queryHistory(String userId, String conversationId, Pageable page);

    /** 清除问答历史；conversationId 为空时清除全部（旧行为）。 */
    void clearHistory(String userId, String conversationId);

    /** 当前用户的对话（会话）列表，按最后活动时间倒序。 */
    List<ConversationVo> listConversations(String userId);

    /** 删除指定对话及其全部消息。 */
    void deleteConversation(String userId, String conversationId);
}
