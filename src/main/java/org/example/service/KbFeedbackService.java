package org.example.service;

import org.example.vo.ChatFeedbackView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Map;

/**
 * 问答反馈服务：用户提交点赞/点踩，管理端分页查询（点踩条目用于回流评测集）。
 */
public interface KbFeedbackService {

    /**
     * 提交一条问答反馈。
     *
     * @param userId         反馈用户（登录态解析）
     * @param conversationId 对话ID（可空）
     * @param rating         UP=点赞 / DOWN=点踩
     * @param question       反馈时的问题快照（可空，超长截断）
     * @param answer         反馈时的回答快照（可空，超长截断）
     * @param comment        用户评语（可空，超长截断）
     * @return 落库结果（id、rating）
     */
    Map<String, Object> submit(String userId, String conversationId, String rating,
                               String question, String answer, String comment);

    /** 管理端反馈分页（时间倒序），rating 为空查全部。 */
    Page<ChatFeedbackView> page(String libraryId, String rating, Pageable pageable);
}
