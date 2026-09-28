package org.example.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.entity.KbChatFeedbackEntity;
import org.example.repository.KbChatFeedbackRepository;
import org.example.service.KbFeedbackService;
import org.example.vo.ChatFeedbackView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 问答反馈服务实现：校验 rating 取值与长度截断后落库；查询为轻量分页读。
 */
@Slf4j
@Service
public class KbFeedbackServiceImpl implements KbFeedbackService {

    private static final int MAX_QUESTION = 2000;
    private static final int MAX_ANSWER = 8000;
    private static final int MAX_COMMENT = 1000;

    @Autowired
    private KbChatFeedbackRepository feedbackRepository;

    @Override
    public Map<String, Object> submit(String userId, String conversationId, String rating,
                                      String question, String answer, String comment) {
        String normalized = rating == null ? "" : rating.trim().toUpperCase();
        if (!"UP".equals(normalized) && !"DOWN".equals(normalized)) {
            throw new IllegalArgumentException("rating 仅支持 UP / DOWN");
        }
        KbChatFeedbackEntity entity = new KbChatFeedbackEntity();
        entity.setLibraryId(org.example.security.SecurityUtil.currentLibraryId("default"));
        entity.setUserId(userId);
        entity.setConversationId(trimTo(conversationId, 64));
        entity.setRating(normalized);
        entity.setQuestion(trimTo(question, MAX_QUESTION));
        entity.setAnswer(trimTo(answer, MAX_ANSWER));
        entity.setComment(trimTo(comment, MAX_COMMENT));
        feedbackRepository.save(entity);
        log.info("问答反馈已提交 - user: {}, conversation: {}, rating: {}, comment: {}",
                userId, entity.getConversationId(), normalized,
                comment == null ? "-" : "有");
        return Map.of("id", entity.getId(), "rating", normalized);
    }

    @Override
    public Page<ChatFeedbackView> page(String libraryId, String rating, Pageable pageable) {
        boolean rated = rating != null && !rating.isBlank();
        Page<KbChatFeedbackEntity> result = rated
                ? feedbackRepository.findByLibraryIdAndRating(libraryId, rating.trim().toUpperCase(), pageable)
                : feedbackRepository.findByLibraryId(libraryId, pageable);
        return result.map(ChatFeedbackView::of);
    }

    private String trimTo(String value, int max) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        return v.length() <= max ? v : v.substring(0, max);
    }
}
