package com.techcodex.repository;

import com.techcodex.entity.KbDocumentStatus;
import com.techcodex.entity.KnowledgeDocumentEntity;
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

    /**
     * 一次取回各状态文档数，替代三次独立 count。
     * 管理台总览每次刷新都会拉统计，三次 count 会把文档表扫三遍。
     */
    @Query("select d.status, count(d) from KnowledgeDocumentEntity d where d.libraryId = :libraryId group by d.status")
    List<Object[]> countGroupByStatus(@Param("libraryId") String libraryId);

    Page<KnowledgeDocumentEntity> findByLibraryId(String libraryId, Pageable pageable);

    /** 治理扫描：一次取全量文档（公共库约 4.5k 篇，内存扫描可接受）。 */
    java.util.List<KnowledgeDocumentEntity> findAllByLibraryId(String libraryId);

    @Query("select d from KnowledgeDocumentEntity d where d.libraryId = :libraryId and (d.title like %:kw% or d.fileName like %:kw%)")
    Page<KnowledgeDocumentEntity> searchByKeywordInLibrary(@Param("libraryId") String libraryId, @Param("kw") String keyword, Pageable pageable);

    /**
     * CMS 内容列表：按来源类型隔离，keyword 匹配标题/路径，categoryDir 精确过滤。
     * keyword/categoryDir 传 null 表示不过滤；CMS 文档量约 4.5k，分页查询走组合索引。
     */
    @Query("select d from KnowledgeDocumentEntity d where d.libraryId = :libraryId and d.sourceType = :sourceType "
            + "and (:keyword is null or d.title like %:keyword% or d.filePath like %:keyword%) "
            + "and (:categoryDir is null or d.categoryDir = :categoryDir)")
    Page<KnowledgeDocumentEntity> searchCmsDocuments(@Param("libraryId") String libraryId,
                                                     @Param("sourceType") String sourceType,
                                                     @Param("keyword") String keyword,
                                                     @Param("categoryDir") String categoryDir,
                                                     Pageable pageable);

    /**
     * 站点公开索引：只取导航元数据，不加载 content LONGTEXT。
     * 按 filePath 排序保证同一份数据库每次导出的顺序稳定（sitemap/RSS 不抖动）。
     *
     * <p>来源用白名单集合而不是单一 CMS：CMS 启用后 SYNC 成为历史导入源，
     * 但在 CMS 尚未发布任何文档时，SYNC 就是站点唯一内容，读取层不能因此把整站读空。
     * 发布状态仍严格要求 PUBLISHED，草稿不会因为来源放宽而泄露。</p>
     */
    @Query("select new com.techcodex.dto.ContentIndexItem(d.id, d.title, d.description, "
            + "d.categorySlug, d.categoryDir, d.docSlug, d.filePath, d.fileSize, "
            + "d.createdDate, d.updatedDate, d.publishedAt, d.revision) "
            + "from KnowledgeDocumentEntity d "
            + "where d.libraryId = :libraryId and d.sourceType in :sourceTypes "
            + "and d.publishStatus = :publishStatus "
            + "and d.categorySlug is not null and d.docSlug is not null "
            + "order by d.filePath asc")
    List<com.techcodex.dto.ContentIndexItem> findPublishedContentIndex(@Param("libraryId") String libraryId,
                                                                     @Param("sourceTypes") java.util.Collection<String> sourceTypes,
                                                                     @Param("publishStatus") String publishStatus);

    /** 单个分类的全量正文（llms 分类全文一次拉取，避免前端逐篇请求 100+ 次）。 */
    @Query("select d from KnowledgeDocumentEntity d where d.libraryId = :libraryId "
            + "and d.sourceType in :sourceTypes and d.publishStatus = :publishStatus "
            + "and d.categorySlug = :categorySlug order by d.filePath asc")
    List<KnowledgeDocumentEntity> findPublishedByCategory(@Param("libraryId") String libraryId,
                                                          @Param("sourceTypes") java.util.Collection<String> sourceTypes,
                                                          @Param("publishStatus") String publishStatus,
                                                          @Param("categorySlug") String categorySlug);

    /** 精确标题引用召回：查询文本中完整出现文档标题时，直接取该文档（用于“结合《A》和《B》”类多文档问题）。 */
    @Query("select d from KnowledgeDocumentEntity d where d.libraryId in :libraryIds and locate(replace(d.title, ' ', ''), replace(:query, ' ', '')) > 0")
    List<KnowledgeDocumentEntity> findTitleMentioned(@Param("libraryIds") java.util.Collection<String> libraryIds,
                                                     @Param("query") String query, Pageable pageable);

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

    /** 空间内按课程分类过滤（frontmatter category，检索范围限定用）：category 精确匹配的 docId 集合。 */
    @Query("select d.id from KnowledgeDocumentEntity d where d.libraryId = :libraryId and d.category = :category")
    java.util.Set<Long> findDocIdsByLibraryAndCategory(@Param("libraryId") String libraryId, @Param("category") String category);

    /** 空间内课程分类去重清单（frontmatter category 升序，供前端问答/检索过滤器）。 */
    @Query("select distinct d.category from KnowledgeDocumentEntity d where d.libraryId = :libraryId and d.category is not null order by d.category")
    java.util.List<String> findDistinctCategories(@Param("libraryId") String libraryId);
}
