package com.techcodex.service.impl;

import lombok.extern.slf4j.Slf4j;
import com.techcodex.entity.KbChatFeedbackEntity;
import com.techcodex.repository.KbChatFeedbackRepository;
import com.techcodex.service.KbFeedbackService;
import com.techcodex.vo.ChatFeedbackView;
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

    /** 内容缺口旁路：点踩是四类信号之一，写入失败不影响反馈提交。 */
    @Autowired
    private com.techcodex.service.ContentGapService contentGapService;

    @Override
    public Map<String, Object> submit(String userId, String conversationId, String rating,
                                      String question, String answer, String comment) {
        String normalized = rating == null ? "" : rating.trim().toUpperCase();
        if (!"UP".equals(normalized) && !"DOWN".equals(normalized)) {
            throw new IllegalArgumentException("rating 仅支持 UP / DOWN");
        }
        KbChatFeedbackEntity entity = new KbChatFeedbackEntity();
        entity.setLibraryId(com.techcodex.security.SecurityUtil.currentLibraryId("default"));
        entity.setUserId(userId);
        entity.setConversationId(trimTo(conversationId, 64));
        entity.setRating(normalized);
        entity.setQuestion(trimTo(question, MAX_QUESTION));
        entity.setAnswer(trimTo(answer, MAX_ANSWER));
        entity.setComment(trimTo(comment, MAX_COMMENT));
        feedbackRepository.save(entity);
        if ("DOWN".equals(normalized)) {
            // 反馈落在登录用户的个人库，但公共问答的缺口应归到 default 才能在后台运营；
            // 对话绑定由问答请求线程写入，反馈紧随回答提交，通常仍在 15 分钟 TTL 内。
            java.util.List<String> bound = com.techcodex.tools.KbLibraryTools.librariesOf(entity.getConversationId());
            String gapLibrary = bound.contains(com.techcodex.library.KbLibrary.DEFAULT_LIBRARY)
                    ? com.techcodex.library.KbLibrary.DEFAULT_LIBRARY
                    : (bound.isEmpty() ? entity.getLibraryId() : bound.get(0));
            contentGapService.recordSignal(gapLibrary, userId, entity.getConversationId(),
                    entity.getQuestion(), "THUMB_DOWN", java.util.List.of(), entity.getId(),
                    comment == null ? "用户点踩" : comment);
        }
        log.info("问答反馈已提交 - user: {}, conversation: {}, rating: {}, comment: {}",
                userId, entity.getConversationId(), normalized,
                comment == null ? "-" : "有");
        return Map.of("id", entity.getId(), "rating", normalized);
    }

    @Override
    public Page<ChatFeedbackView> page(String libraryId, String rating, Pageable pageable) {
        boolean rated = rating != null && !rating.isBlank();
        String normalizedRating = rated ? rating.trim().toUpperCase() : null;
        // libraryId 为空表示管理端跨空间口径：公共库与个人库的反馈都要能看到，
        // 否则周报有数据、反馈工作台却显示空。个人空间页仍传显式 libraryId。
        Page<KbChatFeedbackEntity> result;
        if (libraryId == null || libraryId.isBlank()) {
            result = rated
                    ? feedbackRepository.findByRating(normalizedRating, pageable)
                    : feedbackRepository.findAll(pageable);
        } else {
            result = rated
                    ? feedbackRepository.findByLibraryIdAndRating(libraryId, normalizedRating, pageable)
                    : feedbackRepository.findByLibraryId(libraryId, pageable);
        }
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
