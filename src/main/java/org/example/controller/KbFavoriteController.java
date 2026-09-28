package org.example.controller;

import org.example.dto.KbFavoriteView;
import org.example.service.KbFavoriteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文档收藏接口（/api/kb/favorites，登录即可）。
 * 收藏归属以登录用户名为准，支持公共与个人空间的文档。
 */
@RestController
@RequestMapping("/api/kb/favorites")
public class KbFavoriteController {

    @Autowired
    private KbFavoriteService kbFavoriteService;

    private String currentUser() {
        return org.example.security.SecurityUtil.currentUsername();
    }

    /** 我的收藏分页（收藏时间倒序）。 */
    @GetMapping
    public PagedModel<KbFavoriteView> list(
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable page) {
        Page<KbFavoriteView> result = kbFavoriteService.list(currentUser(), page);
        return new PagedModel<>(result);
    }

    /** 收藏文档（幂等）。 */
    @PostMapping("/{docId}")
    public KbFavoriteView add(@PathVariable("docId") Long docId) {
        return kbFavoriteService.add(currentUser(), docId);
    }

    /** 取消收藏（幂等）。 */
    @DeleteMapping("/{docId}")
    public void remove(@PathVariable("docId") Long docId) {
        kbFavoriteService.remove(currentUser(), docId);
    }

    /** 单文档收藏态回显（详情页星标）。 */
    @GetMapping("/{docId}/status")
    public java.util.Map<String, Boolean> status(@PathVariable("docId") Long docId) {
        return java.util.Map.of("favorite", kbFavoriteService.isFavorite(currentUser(), docId));
    }

    /** 按 docs 相对路径解析公共文档收藏态（文档站详情页星标回显，未命中返回 404）。 */
    @org.springframework.web.bind.annotation.GetMapping("/by-path")
    public org.springframework.http.ResponseEntity<java.util.Map<String, Object>> byPath(
            @org.springframework.web.bind.annotation.RequestParam("path") String path) {
        return kbFavoriteService.resolveByPath(currentUser(), path)
                .<org.springframework.http.ResponseEntity<java.util.Map<String, Object>>>map(
                        pf -> org.springframework.http.ResponseEntity.ok(java.util.Map.of(
                                "docId", pf.docId(), "favorite", pf.favorite())))
                .orElse(org.springframework.http.ResponseEntity.notFound().build());
    }
}
