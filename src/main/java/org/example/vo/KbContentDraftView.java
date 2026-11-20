package org.example.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 缺口转内容生产：给出文章标题、结构大纲与待查证问题，供运营快速起草。 */
@Data
@Builder
public class KbContentDraftView {

    private Long gapId;
    private String sourceQuestion;
    private String title;
    private String summary;
    private List<Section> outline;
    private List<String> researchQuestions;
    private String generatedBy;

    @Data
    @Builder
    public static class Section {
        private String heading;
        private List<String> points;
    }
}
