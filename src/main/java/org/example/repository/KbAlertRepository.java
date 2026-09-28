package org.example.repository;

import org.example.entity.KbAlertEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

/**
 * 运营告警仓库。
 */
public interface KbAlertRepository extends BaseRepository<KbAlertEntity> {

    /** 未处理告警（时间倒序），总览页展示。 */
    List<KbAlertEntity> findByResolvedFalseOrderByCreatedDateDesc();

    /** 告警去重：同类型未处理告警只需存在一条。 */
    Optional<KbAlertEntity> findByTypeAndResolvedFalse(String type);

    /** 告警分页（时间倒序），resolved 为空查全部。 */
    Page<KbAlertEntity> findByLibraryIdAndResolved(String libraryId, boolean resolved, Pageable pageable);

    Page<KbAlertEntity> findByLibraryId(String libraryId, Pageable pageable);
}
