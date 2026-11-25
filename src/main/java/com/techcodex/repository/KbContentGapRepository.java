package com.techcodex.repository;

import com.techcodex.entity.KbContentGapEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Date;
import java.util.List;
import java.util.Optional;

public interface KbContentGapRepository extends BaseRepository<KbContentGapEntity> {

    Optional<KbContentGapEntity> findByLibraryIdAndNormalizedHash(String libraryId, String normalizedHash);

    Page<KbContentGapEntity> findByLibraryId(String libraryId, Pageable pageable);

    Page<KbContentGapEntity> findByLibraryIdAndStatus(String libraryId, String status, Pageable pageable);

    @Query("select g from KbContentGapEntity g where g.libraryId = :libraryId " +
            "and (:status is null or g.status = :status) " +
            "and (:keyword is null or lower(g.question) like lower(concat('%', :keyword, '%')))")
    Page<KbContentGapEntity> search(@Param("libraryId") String libraryId,
                                    @Param("status") String status,
                                    @Param("keyword") String keyword,
                                    Pageable pageable);

    @Query("select g from KbContentGapEntity g where g.libraryId = :libraryId " +
            "and g.status not in ('RESOLVED','IGNORED') order by g.frequency desc, g.lastSeen desc")
    List<KbContentGapEntity> findTopOpen(@Param("libraryId") String libraryId, Pageable pageable);

    long countByLibraryId(String libraryId);

    long countByLibraryIdAndStatus(String libraryId, String status);

    /**
     * 缺口 → 评测用例是 1:N 的弱关联（一个缺口只转一次，但同一 caseId 可能被多条历史缺口引用），
     * 所以按 caseId 返回列表而不是 Optional，回写时逐条判断状态。
     */
    List<KbContentGapEntity> findByEvalCaseId(Long evalCaseId);

    @Query("select count(g) from KbContentGapEntity g where g.libraryId = :libraryId and g.lastSeen >= :from")
    long countSince(@Param("libraryId") String libraryId, @Param("from") Date from);
}
