package org.example.repository;

import org.example.entity.KbChatFeedbackEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * 问答反馈仓库：管理端分页查询与评测回流取数。
 */
public interface KbChatFeedbackRepository extends BaseRepository<KbChatFeedbackEntity> {

    Page<KbChatFeedbackEntity> findByLibraryId(String libraryId, Pageable pageable);

    Page<KbChatFeedbackEntity> findByLibraryIdAndRating(String libraryId, String rating, Pageable pageable);

    /** 评测回流：取指定空间的点踩条目（时间正序，供逐条转 Golden Set 用例）。 */
    List<KbChatFeedbackEntity> findByLibraryIdAndRatingOrderByIdAsc(String libraryId, String rating);
}
