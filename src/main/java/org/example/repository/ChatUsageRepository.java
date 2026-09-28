package org.example.repository;

import org.example.entity.ChatUsageEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Date;
import java.util.List;

/**
 * 聊天用量统计仓库。
 */
public interface ChatUsageRepository extends BaseRepository<ChatUsageEntity> {

    /** 查询某空间指定时间之后的全部用量记录（汇总/趋势用，数据量小，Java 侧聚合）。 */
    List<ChatUsageEntity> findByLibraryIdAndCreatedDateAfterOrderByIdAsc(String libraryId, Date from);

    /** 某空间用量记录按时间倒序分页（明细分页用）。 */
    Page<ChatUsageEntity> findByLibraryIdOrderByIdDesc(String libraryId, Pageable page);

    /**
     * 按用户聚合用量（用户维度 Top 排行）：返回行结构
     * [userId, questions, totalTokens, avgElapsedMs, avgHitCount, lastActiveAt]。
     */
    @Query("select u.userId, count(u), sum(coalesce(u.totalTokens, 0)), avg(coalesce(u.elapsedMs, 0)), " +
            "avg(coalesce(u.hitCount, 0)), max(u.createdDate) " +
            "from ChatUsageEntity u where u.libraryId = :libraryId and u.createdDate >= :from " +
            "group by u.userId order by count(u) desc")
    List<Object[]> aggregateByUser(@Param("libraryId") String libraryId, @Param("from") Date from);
}
