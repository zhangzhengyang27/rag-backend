package com.techcodex.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 学习型 AI 的导读结果：一分钟概览 + 分节要点 + 自测题。
 * 结构固定，前端可直接渲染；AI 不可用时由标题/正文抽取降级。
 */
@Data
@Builder
public class KbLearningGuideView {

    private Long docId;
    private String title;
    private String summary;
    private List<Section> sections;
    private List<Quiz> quiz;
    /** AI=模型生成；FALLBACK=模型不可用时的抽取式导读 */
    private String generatedBy;

    @Data
    @Builder
    public static class Section {
        private String title;
        private List<String> points;
    }

    @Data
    @Builder
    public static class Quiz {
        private String question;
        private List<String> options;
        private String answer;
    }
}
