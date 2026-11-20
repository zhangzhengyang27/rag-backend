package org.example.repository;

import org.example.entity.KbContentGapEventEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface KbContentGapEventRepository extends BaseRepository<KbContentGapEventEntity> {
    Page<KbContentGapEventEntity> findByGapIdOrderByIdDesc(Long gapId, Pageable pageable);
}
