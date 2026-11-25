package com.techcodex.controller;

import com.techcodex.dto.KbDocumentView;
import com.techcodex.dto.KbImportTaskView;
import com.techcodex.dto.KbStatsView;
import com.techcodex.dto.SearchOptions;
import com.techcodex.service.KbDocumentService;
import com.techcodex.service.KbEvalService;
import com.techcodex.service.UsageStatService;
import com.techcodex.service.AuditLogService;
import com.techcodex.vo.AuditLogView;
import com.techcodex.vo.ChatUsageView;
import com.techcodex.vo.UsageSummaryView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.techcodex.vo.KbEvalAbResult;
import com.techcodex.vo.KbEvalReportView;
import com.techcodex.vo.KbEvalResult;

import java.util.List;
import java.util.Map;

/**
 * 知识库管理接口（需 ADMIN 角色，见 SecurityConfig /admin/** 规则）。
 * 提供文档上传、目录导入、删除、索引重建与统计。
 */
@RestController
@RequestMapping("/admin/kb")
public class KbAdminController {

    @Autowired
    private KbDocumentService kbDocumentService;

    @Autowired
    private KbEvalService kbEvalService;

    @Autowired
    private UsageStatService usageStatService;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private com.techcodex.service.KbFeedbackService kbFeedbackService;

    @Autowired
    private com.techcodex.service.KbWeeklyReportService kbWeeklyReportService;

    @Autowired
    private com.techcodex.service.KbTraceService kbTraceService;

    @Autowired
    private com.techcodex.service.KbGovernanceService kbGovernanceService;

    @Autowired
    private com.techcodex.service.KbContentLintService kbContentLintService;

    @Autowired
    private com.techcodex.service.KbGitSyncService kbGitSyncService;

    @Autowired
    private com.techcodex.service.KbSectionBackfillService kbSectionBackfillService;

    @Autowired
    private com.techcodex.service.KbDeepDecisionService kbDeepDecisionService;

    @Autowired
    private com.techcodex.service.KbDeepAnnotationService kbDeepAnnotationService;

    private String currentUserId() {
        // principal 为 record 类型时 getName() 返回 toString()，必须显式取 username
        return com.techcodex.security.SecurityUtil.currentUsername();
    }

    /** 管理端固定操作公共知识库空间（个人空间由用户侧 /api/kb/my/** 自治）。 */
    private String resolveLibraryId() {
        return com.techcodex.library.KbLibrary.DEFAULT_LIBRARY;
    }

    @GetMapping("/documents")
    public PagedModel<KbDocumentView> listDocuments(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) String status,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable page) {
        Page<KbDocumentView> result = kbDocumentService.list(keyword, status, resolveLibraryId(), page);
        return new PagedModel<>(result);
    }

    /** 重建单篇文档索引（同步执行，失败文档修复入口）。 */
    @PostMapping("/documents/{id}/reindex")
    public void reindexDocument(@PathVariable("id") Long id) {
        kbDocumentService.reindex(id, resolveLibraryId());
    }

    /**
     * 在线编辑文档（可选标题）并重建索引；docs 同步受管（SYNC）文档拒绝编辑。
     * title/content 走表单体（content 为 Markdown 原文）。
     */
    @org.springframework.web.bind.annotation.PutMapping("/documents/{id}/content")
    public KbDocumentView updateContent(@PathVariable("id") Long id,
                                        @RequestParam(value = "title", required = false) String title,
                                        @RequestParam("content") String content) {
        return kbDocumentService.updateContent(id, resolveLibraryId(), title, content, currentUserId());
    }

    @GetMapping("/documents/{id}")
    public KbDocumentView getDocument(@PathVariable("id") Long id,
                                      @RequestParam(value = "content", defaultValue = "false") boolean content) {
        return kbDocumentService.get(id, resolveLibraryId(), content);
    }

    @PostMapping("/documents")
    public KbDocumentView upload(@RequestParam("file") MultipartFile file,
                                 @RequestParam(value = "title", required = false) String title) {
        return kbDocumentService.upload(file, title, currentUserId(), resolveLibraryId());
    }

    @PostMapping("/documents/import")
    public Map<String, String> importDirectory(@RequestParam("path") String path) {
        String taskId = kbDocumentService.importDirectory(path, currentUserId(), resolveLibraryId());
        return Map.of("taskId", taskId);
    }

    /** URL 抓取导入：抓取网页正文转 Markdown 后落为一条文档记录。 */
    @PostMapping("/documents/import-url")
    public KbDocumentView importUrl(@RequestParam("url") String url) {
        return kbDocumentService.importUrl(url, currentUserId(), resolveLibraryId());
    }

    @GetMapping("/tasks/{taskId}")
    public KbImportTaskView getTask(@PathVariable("taskId") String taskId) {
        return kbDocumentService.getImportTask(taskId);
    }

    @DeleteMapping("/documents/{id}")
    public void delete(@PathVariable("id") Long id) {
        kbDocumentService.delete(id, resolveLibraryId());
    }

    /** 手动触发 docs 目录自动同步（增量对账：新增/更新/改名/删除）；path 缺省用配置的同步根目录。 */
    @PostMapping("/documents/sync")
    public Map<String, String> syncDocs(@RequestParam(value = "path", required = false) String path) {
        String taskId = kbDocumentService.syncDocs(path, currentUserId(), resolveLibraryId());
        return Map.of("taskId", taskId);
    }

    @PostMapping("/index/rebuild")
    public Map<String, String> rebuildIndex() {
        String taskId = kbDocumentService.rebuildIndex(resolveLibraryId());
        return Map.of("taskId", taskId);
    }

    @GetMapping("/stats")
    public KbStatsView stats() {
        return kbDocumentService.stats(resolveLibraryId());
    }

    /** 知识治理健康度汇总：重复/过期/缺责任人/空分块与平均健康分。 */
    @GetMapping("/governance/summary")
    public com.techcodex.vo.KbGovernanceSummaryView governanceSummary(
            @RequestParam(value = "staleDays", defaultValue = "180") int staleDays) {
        return kbGovernanceService.summary(resolveLibraryId(), staleDays);
    }

    /** 深度检索（GraphRAG/多跳）可选门禁：用评测/延迟数据判断是否值得立项。 */
    @GetMapping("/deep/gate")
    public java.util.Map<String, Object> deepGate() {
        return kbDeepDecisionService.gate();
    }

    /** 父子分块父级回填：只聚合 section + 回填 parent_id，不重算向量。 */
    @PostMapping("/sections/backfill")
    public java.util.Map<String, Object> backfillSections() {
        var result = kbSectionBackfillService.backfill();
        auditLogService.record(resolveLibraryId(), currentUserId(), "SECTION_BACKFILL", "SECTION", null,
                "父子分块父级回填：sections=" + result.sections() + ", chunks=" + result.chunks());
        return java.util.Map.of("sections", result.sections(), "chunks", result.chunks());
    }

    /** GitHub 增量同步：浅克隆/更新仓库后复用目录导入，返回导入任务 ID。 */
    @PostMapping("/sources/github/sync")
    public com.techcodex.vo.KbGitSyncView syncGithub(
            @RequestParam("repoUrl") String repoUrl,
            @RequestParam(value = "branch", defaultValue = "main") String branch,
            @RequestParam(value = "path", defaultValue = "") String path) {
        var view = kbGitSyncService.sync(repoUrl, branch, path, currentUserId(), resolveLibraryId());
        auditLogService.record(resolveLibraryId(), currentUserId(), "GITHUB_SYNC", "SOURCE", null,
                "GitHub 同步：" + view.getRepoUrl() + " " + view.getBranch() + " " + view.getPath());
        return view;
    }

    /** 全站内容 Lint：断链/同名标题 + 可粘贴 PR 的修复清单（只提示，不自动改稿）。 */
    @GetMapping("/lint")
    public com.techcodex.vo.KbLintReportView contentLint(
            @RequestParam(value = "limit", defaultValue = "200") int limit) {
        return kbContentLintService.lint(resolveLibraryId(), limit);
    }

    /** 知识治理问题列表（按健康分升序，最差的先出）；type 可选。 */
    @GetMapping("/governance/issues")
    public List<com.techcodex.vo.KbGovernanceIssueView> governanceIssues(
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "staleDays", defaultValue = "180") int staleDays,
            @RequestParam(value = "limit", defaultValue = "200") int limit) {
        return kbGovernanceService.issues(resolveLibraryId(), type, staleDays, limit);
    }

    /** 维护文档责任人与复核日期；owner 传空串表示清空。 */
    /** 按一级目录批量分派责任人到团队队列；人工 owner 不覆盖。 */
    @PostMapping("/governance/assign-owners")
    public java.util.Map<String, Integer> assignGovernanceOwners(
            @RequestParam(required = false) String libraryId) {
        String targetLibrary = libraryId == null || libraryId.isBlank() ? resolveLibraryId() : libraryId;
        var result = kbGovernanceService.assignOwnersByCategory(targetLibrary);
        auditLogService.record(targetLibrary, currentUserId(), "GOVERNANCE_ASSIGN_OWNERS", "DOCUMENT", null,
                "按目录批量分派责任人：assigned=" + result.get("assigned") + ", kept=" + result.get("kept"));
        return result;
    }

    @PutMapping("/governance/documents/{id}")
    public Map<String, Object> updateGovernance(
            @PathVariable("id") Long id,
            @RequestParam(value = "owner", required = false) String owner,
            @RequestParam(value = "reviewDueDate", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd") java.util.Date reviewDueDate) {
        kbGovernanceService.update(resolveLibraryId(), id, owner, reviewDueDate, currentUserId());
        auditLogService.record(resolveLibraryId(), currentUserId(), "GOVERNANCE_UPDATE", "DOCUMENT", id,
                "更新治理信息：owner=" + (owner == null ? "" : owner));
        return Map.of("id", id);
    }

    /**
     * 运营周报：近 N 天的用量汇总/按日趋势 + 反馈点赞点踩与点踩率 + 点踩 Top 问题榜（内容补强清单）
     * + 检索质量趋势（评测日报）+ 打开中的告警数。
     */
    @GetMapping("/report")
    public com.techcodex.vo.KbWeeklyReportView weeklyReport(
            @RequestParam(value = "days", defaultValue = "30") int days) {
        return kbWeeklyReportService.weekly(resolveLibraryId(), days);
    }

    /**
     * 运行 RAG 检索质量离线评测（基于 Golden Set，计算召回命中率）。以显式空间身份执行。
     */
    @GetMapping("/eval/run")
    public KbEvalResult runEval(@RequestParam(value = "limit", required = false) Integer limit,
                                @RequestParam(value = "topK", required = false) Integer topK) {
        return kbEvalService.run(resolveLibraryId(), limit, topK);
    }

    /** 生成 GraphRAG 门禁用的文档级实体/关系标注样本。 */
    @PostMapping("/deep/annotations/seed")
    public Map<String, Object> seedDeepAnnotations(
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        Map<String, Object> result = kbDeepAnnotationService.seedLinkGraph(limit, resolveLibraryId());
        auditLogService.record(resolveLibraryId(), currentUserId(), "DEEP_ANNOTATION_SEED",
                "DEEP_ANNOTATION", null, "生成文档级实体关系样本：" + result.get("annotated") + " 篇");
        return result;
    }

    /** GraphRAG 门禁标注样本汇总。 */
    @GetMapping("/deep/annotations/summary")
    public Map<String, Object> deepAnnotationSummary() {
        return kbDeepAnnotationService.summary(resolveLibraryId());
    }

    /** 多跳评测：Top-K 必须同时召回全部期望文档，且每个文档至少覆盖一个关键词。 */
    @GetMapping("/eval/multi-hop")
    public KbEvalResult runMultiHop(@RequestParam(value = "limit", required = false) Integer limit,
                                    @RequestParam(value = "topK", required = false) Integer topK) {
        return kbEvalService.runMultiHop(resolveLibraryId(), limit, topK);
    }

    /**
     * 检索 A/B 对比：同用例集、两组检索参数各跑一遍。
     *
     * <p>base* 与 var* 参数分别覆盖基线组与变体组的检索配置，未传的字段沿用全局配置；
     * 常用对比如 baseRerank=true&amp;varRerank=false 比较语义重排开关的影响。</p>
     */
    @GetMapping("/eval/ab")
    public KbEvalAbResult runEvalAb(
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "topK", required = false) Integer topK,
            @RequestParam(value = "baseRerank", required = false) Boolean baseRerank,
            @RequestParam(value = "baseBm25", required = false) Boolean baseBm25,
            @RequestParam(value = "baseTopK", required = false) Integer baseTopK,
            @RequestParam(value = "varRerank", required = false) Boolean varRerank,
            @RequestParam(value = "varBm25", required = false) Boolean varBm25,
            @RequestParam(value = "varTopK", required = false) Integer varTopK) {
        SearchOptions baseline = toOptions(baseRerank, baseBm25, baseTopK);
        SearchOptions variant = toOptions(varRerank, varBm25, varTopK);
        String baselineLabel = labelOf("基线", baseRerank, baseBm25, baseTopK);
        String variantLabel = labelOf("变体", varRerank, varBm25, varTopK);
        return kbEvalService.runAb(resolveLibraryId(), baselineLabel, baseline, variantLabel, variant, limit, topK);
    }

    /** 组装非空字段的检索参数覆盖快照（全部为空时沿用全局配置）。 */
    private SearchOptions toOptions(Boolean rerank, Boolean bm25, Integer topK) {
        SearchOptions options = SearchOptions.of();
        if (rerank != null) {
            options.rerankEnabled(rerank);
        }
        if (bm25 != null) {
            options.bm25Enabled(bm25);
        }
        if (topK != null) {
            options.topK(topK);
        }
        return options;
    }

    /** 根据实际覆盖字段生成可读的组标签。 */
    private String labelOf(String prefix, Boolean rerank, Boolean bm25, Integer topK) {
        StringBuilder sb = new StringBuilder(prefix);
        if (rerank != null) {
            sb.append("·rerank=").append(rerank ? "on" : "off");
        }
        if (bm25 != null) {
            sb.append("·bm25=").append(bm25 ? "on" : "off");
        }
        if (topK != null) {
            sb.append("·topK=").append(topK);
        }
        return sb.toString();
    }

    /**
     * 查询最近 N 天评测趋势（按日期倒序），用于管理页趋势图与回归观察。
     *
     * @param days 最多返回的天数（1~90，默认 30）
     */
    @GetMapping("/eval/trend")
    public List<KbEvalReportView> evalTrend(@RequestParam(value = "days", defaultValue = "30") int days) {
        return kbEvalService.trend(resolveLibraryId(), days);
    }

    /**
     * 评测门禁：CI/发布前调用；返回 passed 与逐项阈值，不直接抛 4xx。
     * 门禁只跑 RETRIEVAL 类 Golden Set，格式/引用类不计入检索质量。
     */
    @GetMapping("/eval/gate")
    public Map<String, Object> evalGate(@RequestParam(value = "topK", defaultValue = "5") int topK) {
        KbEvalResult result = kbEvalService.run(resolveLibraryId(), null, topK);
        java.util.List<Map<String, Object>> checks = new java.util.ArrayList<>();
        boolean passed = true;
        passed &= addCheck(checks, "caseCount", result.getTotal(), 20, "≥");
        passed &= addCheck(checks, "hitRate", result.getHitRate(), 0.45, "≥");
        passed &= addCheck(checks, "ndcg", result.getNdcg(), 0.55, "≥");
        passed &= addCheck(checks, "contextPrecision", result.getContextPrecision(), 0.20, "≥");
        passed &= addCheck(checks, "contextRecall", result.getContextRecall(), 0.70, "≥");
        return Map.of("passed", passed, "checks", checks, "metrics", result);
    }

    /** 单项门禁；detail 里保留实际值与阈值，CI 日志可读。 */
    private boolean addCheck(List<Map<String, Object>> checks, String name, double actual,
                             double threshold, String op) {
        boolean ok = actual >= threshold;
        checks.add(Map.of("name", name, "actual", actual, "threshold", threshold, "pass", ok));
        return ok;
    }

    /**
     * 用量统计汇总：近 N 天问答数 / token（估算）/ 平均耗时 / 平均命中 + 按日趋势。
     *
     * <p>管理端看全局：传 null 表示跨公共库与个人库汇总，避免个人空间问答在看板中“消失”。</p>
     */
    @GetMapping("/usage/summary")
    public UsageSummaryView usageSummary(@RequestParam(value = "days", defaultValue = "30") int days) {
        return usageStatService.summary(null, days);
    }

    /** 用量明细分页（时间倒序），管理端跨空间展示。 */
    @GetMapping("/usage/recent")
    public PagedModel<ChatUsageView> usageRecent(
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable page) {
        Page<ChatUsageView> result = usageStatService.recent(null, page);
        return new PagedModel<>(result);
    }

    /**
     * 用户维度用量 Top 排行（按问答数降序）：识别重度用户与异常用量。
     *
     * @param days  统计周期（1~365，默认 7）
     * @param limit 最多返回用户数（1~100，默认 10）
     */
    @GetMapping("/usage/users")
    public List<com.techcodex.vo.UserUsageView> usageTopUsers(
            @RequestParam(value = "days", defaultValue = "7") int days,
            @RequestParam(value = "limit", defaultValue = "10") int limit) {
        return usageStatService.topUsers(null, days, limit);
    }

    /**
     * 审计日志分页查询：libraryId 可选过滤（缺省查全部空间），支持按操作类型过滤。
     */
    @GetMapping("/audit/logs")
    public PagedModel<AuditLogView> auditLogs(
            @RequestParam(value = "libraryId", required = false) String libraryId,
            @RequestParam(value = "action", required = false) String action,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable page) {
        Page<AuditLogView> result = auditLogService.query(
                libraryId == null || libraryId.isBlank() ? null : libraryId, action, page);
        return new PagedModel<>(result);
    }

    /**
     * 新增一个 RAG 评测用例（Golden Set）。
     */
    @PostMapping("/eval/cases")
    public Map<String, Object> addEvalCase(@RequestParam("question") String question,
                                           @RequestParam("expectedKeywords") String expectedKeywords,
                                           @RequestParam(value = "expectedDocIds", required = false) String expectedDocIds,
                                           @RequestParam(value = "note", required = false) String note,
                                           @RequestParam(value = "caseType", required = false) String caseType) {
        Long id = kbEvalService.addCase(question, expectedKeywords, expectedDocIds, note, currentUserId(), caseType);
        auditLogService.record(resolveLibraryId(), currentUserId(), "EVAL_CASE_ADD", "EVAL_CASE", id,
                "新增评测用例 #" + id + "：" + question);
        return Map.of("id", id);
    }

    /** 评测用例（Golden Set）分页（新增倒序），供管理端维护。 */
    @GetMapping("/eval/cases")
    public PagedModel<com.techcodex.vo.KbEvalCaseView> evalCases(
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable page) {
        return new PagedModel<>(kbEvalService.listCases(page));
    }

    /** 删除评测用例。 */
    @DeleteMapping("/eval/cases/{id}")
    public void deleteEvalCase(@PathVariable("id") Long id) {
        kbEvalService.deleteCase(id);
        auditLogService.record(resolveLibraryId(), currentUserId(), "EVAL_CASE_DELETE", "EVAL_CASE", id,
                "删除评测用例 #" + id);
    }

    /**
     * 点踩反馈一键回流为评测用例：问题取反馈快照，期望关键字由管理员给定，
     * 备注缺省记录反馈来源与用户评语。
     */
    @PostMapping("/eval/cases/from-feedback")
    public Map<String, Object> evalCaseFromFeedback(@RequestParam("feedbackId") Long feedbackId,
                                                    @RequestParam("expectedKeywords") String expectedKeywords,
                                                    @RequestParam(value = "note", required = false) String note) {
        Long id = kbEvalService.addCaseFromFeedback(resolveLibraryId(), feedbackId, expectedKeywords, note, currentUserId());
        auditLogService.record(resolveLibraryId(), currentUserId(), "EVAL_CASE_ADD", "EVAL_CASE", id,
                "点踩反馈 #" + feedbackId + " 回流为评测用例 #" + id);
        return Map.of("id", id);
    }

    /**
     * 问答反馈分页（时间倒序），rating 可选过滤（DOWN=点踩，回流评测集的工作台）。
     */
    @GetMapping("/feedback")
    public PagedModel<com.techcodex.vo.ChatFeedbackView> feedback(
            @RequestParam(value = "rating", required = false) String rating,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable page) {
        // 管理端看全局反馈：与周报的口径一致，不限定公共库 default。
        Page<com.techcodex.vo.ChatFeedbackView> result =
                kbFeedbackService.page(null, rating, page);
        return new PagedModel<>(result);
    }

    /**
     * 检索 Trace 分页（时间倒序）：单次混合检索的召回明细，供诊断与调优观测。
     * libraryId / userId / keyword（匹配原始与检索用查询）均可选过滤，缺省查全部空间。
     */
    @GetMapping("/traces")
    public PagedModel<com.techcodex.vo.RetrievalTraceView> traces(
            @RequestParam(value = "libraryId", required = false) String libraryId,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "keyword", required = false) String keyword,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable page) {
        Page<com.techcodex.vo.RetrievalTraceView> result =
                kbTraceService.page(libraryId, userId, keyword, page);
        return new PagedModel<>(result);
    }
}
