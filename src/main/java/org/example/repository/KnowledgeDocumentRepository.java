package org.example.repository;

import org.example.entity.KbDocumentStatus;
import org.example.entity.KnowledgeDocumentEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface KnowledgeDocumentRepository extends BaseRepository<KnowledgeDocumentEntity> {

    boolean existsByContentHashAndLibraryId(String contentHash, String libraryId);

    Optional<KnowledgeDocumentEntity> findByLibraryIdAndFilePath(String libraryId, String filePath);

    Optional<KnowledgeDocumentEntity> findByIdAndLibraryId(Long id, String libraryId);

    /** docs 目录自动同步受管集合（sourceType=SYNC），用于增量对账与失效清理。 */
    List<KnowledgeDocumentEntity> findAllByLibraryIdAndSourceType(String libraryId, String sourceType);

    /** 历史目录导入的存量文档（sourceType 为空），供同步对账收编与失效清理。 */
    List<KnowledgeDocumentEntity> findAllByLibraryIdAndSourceTypeIsNull(String libraryId);

    long countByLibraryId(String libraryId);

    long countByStatusAndLibraryId(KbDocumentStatus status, String libraryId);

    Page<KnowledgeDocumentEntity> findByLibraryId(String libraryId, Pageable pageable);

    @Query("select d from KnowledgeDocumentEntity d where d.libraryId = :libraryId and (d.title like %:kw% or d.fileName like %:kw%)")
    Page<KnowledgeDocumentEntity> searchByKeywordInLibrary(@Param("libraryId") String libraryId, @Param("kw") String keyword, Pageable pageable);

    /** 空间内全部文档的 docId 集合（检索隔离基础）。 */
    @Query("select d.id from KnowledgeDocumentEntity d where d.libraryId = :libraryId")
    java.util.Set<Long> findDocIdsByLibrary(@Param("libraryId") String libraryId);

    /** 空间内目录前缀过滤：filePath 以指定前缀开头的 docId 集合。 */
    @Query("select d.id from KnowledgeDocumentEntity d where d.libraryId = :libraryId and d.filePath like :prefix%")
    java.util.Set<Long> findDocIdsByLibraryAndFilePathPrefix(@Param("libraryId") String libraryId, @Param("prefix") String prefix);

    /** 空间内空间过滤：指定 space 的 docId 集合。 */
    @Query("select d.id from KnowledgeDocumentEntity d where d.libraryId = :libraryId and d.space = :space")
    java.util.Set<Long> findDocIdsByLibraryAndSpace(@Param("libraryId") String libraryId, @Param("space") String space);

    /** 空间内按空间集合过滤：指定多个 space 的 docId 集合（ACL 空间授权用）。 */
    @Query("select d.id from KnowledgeDocumentEntity d where d.libraryId = :libraryId and d.space in :spaces")
    java.util.Set<Long> findDocIdsByLibraryAndSpaceIn(@Param("libraryId") String libraryId, @Param("spaces") java.util.Collection<String> spaces);
}
