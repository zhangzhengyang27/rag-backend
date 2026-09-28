package org.example.repository;

import org.example.entity.ChatHistoryEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ChatHistoryRepository extends BaseRepository<ChatHistoryEntity> {

    /**
     * 获取指定会话最新的20条记录（保留历史接口，供无对话维度调用）
     */
    List<ChatHistoryEntity> findTop20BySessionIdOrderByIdDesc(String sessionId);

    /**
     * 按会话ID分页查询聊天记录（保留历史接口）
     */
    Page<ChatHistoryEntity> findAllBySessionId(String sessionId, Pageable pageable);

    /**
     * 删除指定会话的全部聊天记录（保留历史接口）
     */
    @Transactional
    @Modifying
    void deleteBySessionId(String sessionId);

    /**
     * 指定对话内最近的前几个会话上下文（多轮对话用，倒序含最新）
     */
    List<ChatHistoryEntity> findTop10BySessionIdAndConversationIdOrderByIdDesc(String sessionId, String conversationId);

    /**
     * 指定会话(conversationId)的分页查询
     */
    Page<ChatHistoryEntity> findAllBySessionIdAndConversationId(String sessionId, String conversationId, Pageable pageable);

    /**
     * 删除指定用户下某个对话的全部记录
     */
    @Transactional
    @Modifying
    void deleteBySessionIdAndConversationId(String sessionId, String conversationId);

    /**
     * 当前用户的所有对话聚合信息，按最后一条消息 id 倒序（最近活动的会话在前）。
     * 一次查询带出每个会话的：会话ID、最新消息id（排序）、首条用户消息id（作标题/创建时间）、消息条数，
     * 令会话列表可由少量批量查询组装，避免逐会话 N+1 查询。
     */
    interface ConversationIdRow {
        String getConversationId();

        Long getLastId();

        Long getFirstUserId();

        long getCnt();
    }

    @Query(value = """
            select c.conversation_id as conversationId,
                   max(c.id)                        as lastId,
                   min(case when c.role = 'user' then c.id end) as firstUserId,
                   count(c.id)                      as cnt
            from chat_history c
            where c.session_id = :sessionId and c.conversation_id is not null
            group by c.conversation_id
            order by lastId desc
            """, nativeQuery = true)
    List<ConversationIdRow> findConversationIdsBySessionId(@Param("sessionId") String sessionId);
}