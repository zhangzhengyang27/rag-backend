package org.example.repository;

import org.example.entity.KbEvalReportEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

/**
 * RAG 评测趋势报告仓库（按空间隔离）。
 */
public interface KbEvalReportRepository extends BaseRepository<KbEvalReportEntity> {

    /** 按日期 + 空间查单日报告（用于定时评测幂等去重）。 */
    Optional<KbEvalReportEntity> findByDateAndLibraryId(String date, String libraryId);

    /** 空间内最近 N 天评测趋势（按日期倒序，配合 Pageable 截取）。 */
    Page<KbEvalReportEntity> findByLibraryIdOrderByDateDesc(String libraryId, Pageable pageable);
}
