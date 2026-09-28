package org.example.vo;

import lombok.Builder;
import lombok.Data;
import org.example.entity.KbChatFeedbackEntity;

import java.util.Date;

/**
 * 单条问答反馈视图（管理端列表用）。
 */
@Data
@Builder
public class ChatFeedbackView {

    private Long id;

    private String userId;

    private String conversationId;

    private String rating;

    private String question;

    private String answer;

    private String comment;

    private Date createdDate;

    public static ChatFeedbackView of(KbChatFeedbackEntity e) {
        return ChatFeedbackView.builder()
                .id(e.getId())
                .userId(e.getUserId())
                .conversationId(e.getConversationId())
                .rating(e.getRating())
                .question(e.getQuestion())
                .answer(e.getAnswer())
                .comment(e.getComment())
                .createdDate(e.getCreatedDate())
                .build();
    }
}
