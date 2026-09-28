package org.example.repository;

import org.example.entity.RetrievalTraceEntity;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Date;

/**
 * 知识库检索追踪仓库。
 */
public interface RetrievalTraceRepository extends BaseRepository<RetrievalTraceEntity> {

    /** 保留期清理：删除创建时间早于截止时间的追踪记录（bulk delete，不逐条加载实体）。 */
    @Modifying
    @Query("delete from RetrievalTraceEntity t where t.createdDate < :cutoff")
    long deleteAllByCreatedDateBefore(@Param("cutoff") Date cutoff);
}
