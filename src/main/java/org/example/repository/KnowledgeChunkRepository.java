package org.example.repository;

import org.example.entity.KnowledgeChunkEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface KnowledgeChunkRepository extends BaseRepository<KnowledgeChunkEntity> {

    List<KnowledgeChunkEntity> findByDocIdOrderByChunkIndexAsc(Long docId);

    long countByDocId(Long docId);

    void deleteByDocId(Long docId);

    /** 查询某文档全部分块的 Redis 向量 ID（用于按 ID 精准删除旧向量，避免整库重建）。 */
    @Query("select c.vectorId from KnowledgeChunkEntity c where c.docId = :docId and c.vectorId is not null")
    List<String> findVectorIdsByDocId(@Param("docId") Long docId);

    /**
     * 关键词检索：分块正文包含任一分词即返回（最多返回 limit 条，按分块序号排序保证稳定）。
     * 限定空间维度，避免跨空间泄露。
     */
    @Query(value = "select c from KnowledgeChunkEntity c where c.libraryId = :libraryId and c.text like %:kw% order by c.docId, c.chunkIndex", countQuery = "select count(c) from KnowledgeChunkEntity c where c.libraryId = :libraryId and c.text like %:kw%")
    List<KnowledgeChunkEntity> keywordSearch(@Param("libraryId") String libraryId, @Param("kw") String keyword, org.springframework.data.domain.Pageable pageable);

    @Query("select count(c) from KnowledgeChunkEntity c")
    long countAll();

    /** 空间内全部分块的 Redis 向量 ID（用于按空间增量重建/清理，避免整库重建）。 */
    @Query("select c.vectorId from KnowledgeChunkEntity c where c.libraryId = :libraryId and c.vectorId is not null")
    List<String> findVectorIdsByLibraryId(@Param("libraryId") String libraryId);

    /** 全库所有分块的 Redis 向量 ID（重建后孤儿向量对账用：前缀空间是全局的，引用集合也必须取全局）。 */
    @Query("select c.vectorId from KnowledgeChunkEntity c where c.vectorId is not null")
    List<String> findAllVectorIds();

    /** 空间内分块总数（统计用）。 */
    long countByLibraryId(String libraryId);
}
