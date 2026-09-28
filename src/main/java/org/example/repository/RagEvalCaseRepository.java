package org.example.repository;

import org.example.entity.RagEvalCaseEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * RAG 评测用例仓库。
 */
public interface RagEvalCaseRepository extends BaseRepository<RagEvalCaseEntity> {

    /** 用例分页（新增倒序），供管理端 Golden Set 维护。 */
    Page<RagEvalCaseEntity> findAllByOrderByIdDesc(Pageable pageable);
}
