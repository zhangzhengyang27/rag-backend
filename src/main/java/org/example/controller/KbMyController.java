package org.example.controller;

import org.example.dto.MyDocumentsPage;
import org.example.quota.QuotaUsage;
import org.example.security.AuthenticatedUser;
import org.example.security.SecurityUtil;
import org.example.service.KbDocumentService;
import org.example.library.KbLibrary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 用户私有知识库接口（/api/kb/my/**，登录即可，空间身份一律取自 JWT）。
 *
 * <p>私有文档的列表/上传/删除与配额余量。个人知识库空间为 u-{username}
 * （注册时写入用户默认空间），与公共知识库（default）天然隔离。</p>
 */
@RestController
@RequestMapping("/api/kb/my")
public class KbMyController {

    @Autowired
    private KbDocumentService kbDocumentService;

    @Autowired
    private org.example.quota.QuotaService quotaService;

    private AuthenticatedUser requireUser() {
        return SecurityUtil.currentUser().orElseThrow(() -> new IllegalArgumentException("未登录"));
    }

    /** 当前用户的个人知识库空间标识（u-{username}，纯派生，无 DB 依赖）。 */
    private String personalSpace() {
        return KbLibrary.personalCode(requireUser().username());
    }

    @GetMapping("/documents")
    public MyDocumentsPage documents(@RequestParam(value = "keyword", required = false) String keyword,
                                     @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable page) {
        Page<org.example.dto.KbDocumentView> result = kbDocumentService.list(keyword, null, personalSpace(), page);
        return new MyDocumentsPage(result.getContent(), result.getTotalElements(),
                result.getTotalPages(), result.getNumber(), result.getSize());
    }

    /** 单文档详情（含解析后的 Markdown 原文，供前端阅读器渲染）；非本人空间的文档视为不存在。 */
    @GetMapping("/documents/{id}")
    public org.example.dto.KbDocumentView getDocument(@PathVariable("id") Long id) {
        return kbDocumentService.get(id, personalSpace(), true);
    }

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public org.example.dto.KbDocumentView upload(@RequestParam("file") MultipartFile file,
                                                 @RequestParam(value = "title", required = false) String title) {
        return kbDocumentService.upload(file, title, requireUser().username(), personalSpace());
    }

    @DeleteMapping("/documents/{id}")
    public void deleteDocument(@PathVariable("id") Long id) {
        kbDocumentService.delete(id, personalSpace());
    }

    /** 个人知识库配额余量（文档总数 + 当日检索/问答），前端用量条展示。 */
    @GetMapping("/quota")
    public QuotaUsage quota() {
        return quotaService.usage(personalSpace());
    }
}
