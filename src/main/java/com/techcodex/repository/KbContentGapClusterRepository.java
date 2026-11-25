package com.techcodex.repository;

import com.techcodex.entity.KbContentGapClusterEntity;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface KbContentGapClusterRepository extends BaseRepository<KbContentGapClusterEntity> {
    List<KbContentGapClusterEntity> findByLibraryIdOrderByLastSeenDesc(String libraryId, Pageable pageable);
}
