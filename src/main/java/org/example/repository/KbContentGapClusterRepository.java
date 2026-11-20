package org.example.repository;

import org.example.entity.KbContentGapClusterEntity;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface KbContentGapClusterRepository extends BaseRepository<KbContentGapClusterEntity> {
    List<KbContentGapClusterEntity> findByLibraryIdOrderByLastSeenDesc(String libraryId, Pageable pageable);
}
