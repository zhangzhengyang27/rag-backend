package com.techcodex.repository;

import com.techcodex.entity.KnowledgeSectionEntity;

import java.util.List;
import java.util.Optional;

public interface KnowledgeSectionRepository extends BaseRepository<KnowledgeSectionEntity> {

    Optional<KnowledgeSectionEntity> findByDocIdAndHeadingOrdinal(Long docId, Integer headingOrdinal);

    List<KnowledgeSectionEntity> findByDocIdOrderByHeadingOrdinalAsc(Long docId);

    void deleteByDocId(Long docId);
}
