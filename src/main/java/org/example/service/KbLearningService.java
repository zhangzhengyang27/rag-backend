package org.example.service;

import org.example.vo.KbLearningGuideView;

/** 学习型 AI：按文档生成导读与自测题。 */
public interface KbLearningService {
    KbLearningGuideView guide(String libraryId, Long docId);

    /** 文档站按相对路径生成导读，避免前端为了拿 docId 再发一次检索。 */
    KbLearningGuideView guideByPath(String libraryId, String filePath);
}
