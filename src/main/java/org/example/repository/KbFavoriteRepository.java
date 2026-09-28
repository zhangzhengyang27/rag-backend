package org.example.repository;

import org.example.entity.KbFavoriteEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

/**
 * 文档收藏仓库：按用户查询/维护收藏关系。
 */
public interface KbFavoriteRepository extends org.springframework.data.jpa.repository.JpaRepository<KbFavoriteEntity, Long> {

    /** 某用户的收藏（按收藏时间倒序分页）。 */
    Page<KbFavoriteEntity> findByUsernameOrderByIdDesc(String username, Pageable page);

    /** 单条收藏（幂等判断用）。 */
    Optional<KbFavoriteEntity> findByUsernameAndDocId(String username, Long docId);

    /** 取消收藏（幂等：不存在时无操作）。 */
    void deleteByUsernameAndDocId(String username, Long docId);

    /** 某用户是否已收藏该文档（详情页收藏态回显）。 */
    boolean existsByUsernameAndDocId(String username, Long docId);
}
