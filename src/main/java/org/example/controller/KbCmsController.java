package org.example.controller;

import org.example.dto.CmsDocumentDetailView;
import org.example.dto.CmsDocumentSaveRequest;
import org.example.dto.CmsImportRequest;
import org.example.dto.CmsImportResult;
import org.example.dto.CmsVersionView;
import org.example.dto.KbDocumentView;
import org.example.service.KbCmsService;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 站点内容 CMS 管理接口（/admin/** 由 SecurityConfig 统一要求 ADMIN）。
 *
 * <p>设计边界：CMS 只管站点内容（sourceType=CMS）。上传/URL/个人库仍走
 * KbAdminController 的既有文档接口；CMS 发布后写库并重建向量（镜像可配置关闭），
 * 前端由 Next 的 CMS 代理触发缓存失效，保证页面与侧边栏及时更新。</p>
 */
@RestController
@RequestMapping("/admin/kb/cms")
public class KbCmsController {

    private final KbCmsService kbCmsService;

    public KbCmsController(KbCmsService kbCmsService) {
        this.kbCmsService = kbCmsService;
    }

    private String currentUserId() {
        return org.example.security.SecurityUtil.currentUsername();
    }

    private String resolveLibraryId() {
        return org.example.library.KbLibrary.DEFAULT_LIBRARY;
    }

    @GetMapping("/documents")
    public PagedModel<KbDocumentView> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "categoryDir", required = false) String categoryDir,
            @PageableDefault(size = 20, sort = "updatedDate", direction = Sort.Direction.DESC) Pageable page) {
        return new PagedModel<>(kbCmsService.list(keyword, categoryDir, resolveLibraryId(), page));
    }

    @GetMapping("/documents/{id}")
    public CmsDocumentDetailView detail(@PathVariable("id") Long id) {
        return kbCmsService.detail(id, resolveLibraryId());
    }

    @PostMapping("/documents")
    public CmsDocumentDetailView create(@RequestBody CmsDocumentSaveRequest request) {
        return kbCmsService.create(request, resolveLibraryId(), currentUserId());
    }

    @PutMapping("/documents/{id}")
    public CmsDocumentDetailView update(@PathVariable("id") Long id,
                                        @RequestBody CmsDocumentSaveRequest request) {
        return kbCmsService.update(id, request, resolveLibraryId(), currentUserId());
    }

    @DeleteMapping("/documents/{id}")
    public void delete(@PathVariable("id") Long id) {
        kbCmsService.delete(id, resolveLibraryId(), currentUserId());
    }

    @GetMapping("/documents/{id}/versions")
    public List<CmsVersionView> versions(@PathVariable("id") Long id) {
        return kbCmsService.versions(id, resolveLibraryId());
    }

    @PostMapping("/documents/{id}/versions/{versionId}/restore")
    public CmsDocumentDetailView restore(@PathVariable("id") Long id,
                                         @PathVariable("versionId") Long versionId) {
        return kbCmsService.restore(id, versionId, resolveLibraryId(), currentUserId());
    }

    /** 一次性把 docs/ 目录收编为 CMS；分类映射由前端 docs-config 传入，避免后端重复配置。 */
    @PostMapping("/import")
    public CmsImportResult importDocs(@RequestBody CmsImportRequest request) {
        return kbCmsService.importDocs(request, resolveLibraryId(), currentUserId());
    }
}
