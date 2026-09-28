package org.example.repository;

import org.example.entity.KbChatFeedbackEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Date;
import java.util.List;

/**
 * 问答反馈仓库：管理端分页查询与评测回流取数。
 */
public interface KbChatFeedbackRepository extends BaseRepository<KbChatFeedbackEntity> {

    Page<KbChatFeedbackEntity> findByLibraryId(String libraryId, Pageable pageable);

    Page<KbChatFeedbackEntity> findByLibraryIdAndRating(String libraryId, String rating, Pageable pageable);

    /** 评测回流：取指定空间的点踩条目（时间正序，供逐条转 Golden Set 用例）。 */
    List<KbChatFeedbackEntity> findByLibraryIdAndRatingOrderByIdAsc(String libraryId, String rating);

    /** 周报：周期内某空间某评分（UP/DOWN）的反馈条数。 */
    long countByLibraryIdAndRatingAndCreatedDateAfter(String libraryId, String rating, Date after);

    /**
     * 周报「内容补强清单」：周期内点踩问题按原文聚合计数（降序），附最近评语与时间。
     * 行结构 [question, count, lastComment, lastAt]；无问题文本的条目不参与。
     */
    @Query("select f.question, count(f), max(f.comment), max(f.createdDate) from KbChatFeedbackEntity f " +
            "where f.libraryId = :libraryId and f.rating = 'DOWN' and f.createdDate >= :from " +
            "and f.question is not null " +
            "group by f.question order by count(f) desc")
    List<Object[]> aggregateDownQuestions(@Param("libraryId") String libraryId, @Param("from") Date from);
}
