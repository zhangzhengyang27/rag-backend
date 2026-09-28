package org.example.service;

import org.example.dto.KbFavoriteView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 文档收藏服务：收藏/取消/列表。收藏以 (username, docId) 幂等，文档信息实时联查。
 */
public interface KbFavoriteService {

    /** 某用户的收藏分页（时间倒序）；已删除文档的孤儿收藏会被机会式清理。 */
    Page<KbFavoriteView> list(String username, Pageable page);

    /** 收藏文档；重复收藏幂等返回既有条目。文档不存在时抛业务异常。 */
    KbFavoriteView add(String username, Long docId);

    /** 取消收藏；未收藏时幂等无操作。 */
    void remove(String username, Long docId);

    /** 文档是否已被该用户收藏（详情页收藏态回显）。 */
    boolean isFavorite(String username, Long docId);

    /**
     * 按 docs 相对路径解析公共空间文档的收藏态（文档站详情页星标）。
     * 路径未命中任何公共文档时返回 empty（前端据此隐藏星标）。
     */
    java.util.Optional<PathFavorite> resolveByPath(String username, String path);

    /** 路径解析结果：KB 文档ID + 当前用户收藏态。 */
    record PathFavorite(Long docId, boolean favorite) {
    }
}
