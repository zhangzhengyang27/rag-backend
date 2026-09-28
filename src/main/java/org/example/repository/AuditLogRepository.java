package org.example.repository;

import org.example.entity.AuditLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Date;

/**
 * 审计日志仓库：按空间/操作类型过滤分页查询。
 */
public interface AuditLogRepository extends BaseRepository<AuditLogEntity> {

    /** 某空间全部审计日志，按 id 倒序（时间新在前）。 */
    Page<AuditLogEntity> findByLibraryIdOrderByIdDesc(String libraryId, Pageable page);

    /** 某空间 + 操作类型过滤。 */
    Page<AuditLogEntity> findByLibraryIdAndActionOrderByIdDesc(String libraryId, String action, Pageable page);

    /** 仅按操作类型过滤（跨空间场景）。 */
    Page<AuditLogEntity> findByActionOrderByIdDesc(String action, Pageable page);

    /** 保留期清理：删除创建时间早于截止时间的审计日志（bulk delete，不逐条加载实体）。 */
    @Modifying
    @Query("delete from AuditLogEntity a where a.createdDate < :cutoff")
    long deleteAllByCreatedDateBefore(@Param("cutoff") Date cutoff);
}
