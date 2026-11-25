package com.techcodex.repository;

import com.techcodex.entity.KnowledgeDocumentVersionEntity;

import java.util.List;
import java.util.Optional;

public interface KnowledgeDocumentVersionRepository extends BaseRepository<KnowledgeDocumentVersionEntity> {

    List<KnowledgeDocumentVersionEntity> findByDocIdOrderByRevisionDesc(Long docId);

    Optional<KnowledgeDocumentVersionEntity> findByIdAndDocId(Long id, Long docId);

    long countByDocId(Long docId);

    void deleteByDocId(Long docId);
}
