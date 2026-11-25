package com.techcodex.service;

import com.techcodex.vo.KbContentDraftView;

/** 从高频内容缺口生成可编辑的文章大纲。 */
public interface KbContentDraftService {
    KbContentDraftView draft(String libraryId, Long gapId);
}
