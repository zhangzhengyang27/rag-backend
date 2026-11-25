package com.techcodex.controller;

import com.techcodex.service.AuditLogService;
import com.techcodex.service.ContentGapService;
import com.techcodex.vo.ContentGapClusterView;
import com.techcodex.vo.ContentGapDetailView;
import com.techcodex.vo.ContentGapSummaryView;
import com.techcodex.vo.ContentGapView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 内容缺口中心管理接口（ADMIN）。前台问答信号写入由 ContentGapService 旁路完成。
 * 管理端固定操作公共库；个人库缺口不在后台暴露，避免跨用户隐私。
 */
@RestController
@RequestMapping("/admin/kb/gaps")
public class ContentGapController {

    @Autowired
    private ContentGapService contentGapService;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private com.techcodex.service.KbContentDraftService contentDraftService;

    private String libraryId() {
        return com.techcodex.library.KbLibrary.DEFAULT_LIBRARY;
    }

    private String operator() {
        return com.techcodex.security.SecurityUtil.currentUsername();
    }

    @GetMapping
    public PagedModel<ContentGapView> list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "keyword", required = false) String keyword,
            @PageableDefault(size = 20, sort = "frequency", direction = Sort.Direction.DESC) Pageable pageable) {
        Page<ContentGapView> page = contentGapService.page(libraryId(), status, keyword, pageable);
        return new PagedModel<>(page);
    }

    @GetMapping("/top")
    public List<ContentGapView> top(@RequestParam(value = "limit", defaultValue = "10") int limit) {
        return contentGapService.top(libraryId(), limit);
    }

    @GetMapping("/summary")
    public ContentGapSummaryView summary() {
        return contentGapService.summary(libraryId());
    }

    @GetMapping("/clusters")
    public List<ContentGapClusterView> clusters() {
        return contentGapService.clusters(libraryId());
    }

    @GetMapping("/export.csv")
    public ResponseEntity<byte[]> export(@RequestParam(value = "status", required = false) String status,
                                         @RequestParam(value = "keyword", required = false) String keyword) {
        String csv = contentGapService.exportCsv(libraryId(), status, keyword);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"content-gaps.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/{id}")
    public ContentGapDetailView detail(@PathVariable Long id) {
        return contentGapService.detail(id, libraryId());
    }

    @PostMapping("/{id}/status")
    public ContentGapView updateStatus(@PathVariable Long id,
                                       @RequestParam String status,
                                       @RequestParam(value = "note", required = false) String note) {
        ContentGapView view = contentGapService.updateStatus(id, libraryId(), status, note);
        auditLogService.record(libraryId(), operator(), "GAP_STATUS", "CONTENT_GAP", id,
                "缺口状态 -> " + view.getStatus());
        return view;
    }

    /** 批量流转：ids 用逗号分隔，前端多选后一次提交。 */
    @PostMapping("/batch-status")
    public Map<String, Object> batchStatus(@RequestParam String ids,
                                           @RequestParam String status,
                                           @RequestParam(value = "note", required = false) String note) {
        List<Long> list = Arrays.stream(ids.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(Long::valueOf).toList();
        contentGapService.batchStatus(list, libraryId(), status);
        auditLogService.record(libraryId(), operator(), "GAP_BATCH_STATUS", "CONTENT_GAP", null,
                "批量流转 " + list.size() + " 条缺口 -> " + status);
        return Map.of("updated", list.size(), "status", status);
    }

    /** 缺口转内容生产：生成文章大纲（只生成草案，不自动入库）。 */
    @PostMapping("/{id}/draft")
    public com.techcodex.vo.KbContentDraftView draft(@PathVariable("id") Long id) {
        var draft = contentDraftService.draft(libraryId(), id);
        auditLogService.record(libraryId(), operator(), "GAP_DRAFT", "CONTENT_GAP", id,
                "生成内容大纲：" + (draft.getGeneratedBy() == null ? "" : draft.getGeneratedBy()));
        return draft;
    }

    @PostMapping("/{id}/convert")
    public Map<String, Object> convert(@PathVariable Long id,
                                       @RequestParam String expectedKeywords,
                                       @RequestParam(value = "note", required = false) String note) {
        Long caseId = contentGapService.convertToEval(id, libraryId(), expectedKeywords, note, operator());
        auditLogService.record(libraryId(), operator(), "GAP_CONVERT", "CONTENT_GAP", id,
                "转评测用例 #" + caseId);
        return Map.of("caseId", caseId);
    }
}
