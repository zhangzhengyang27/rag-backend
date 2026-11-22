package org.example.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.service.KbDeepDecisionService;
import org.example.service.KbEvalService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 门禁口径刻意保守：GraphRAG 需要“开放式多跳质量 + 实体/关系抽取 + 额外延迟预算”，
 * 任一不达标就先不立项，避免为了概念上线一个更慢、更难维护的检索链路。
 */
@Slf4j
@Service
public class KbDeepDecisionServiceImpl implements KbDeepDecisionService {

    private final JdbcTemplate jdbcTemplate;
    private final KbEvalService kbEvalService;

    public KbDeepDecisionServiceImpl(JdbcTemplate jdbcTemplate, KbEvalService kbEvalService) {
        this.jdbcTemplate = jdbcTemplate;
        this.kbEvalService = kbEvalService;
    }

    @Override
    public Map<String, Object> gate() {
        int documents = count("SELECT COUNT(*) FROM kb_document WHERE library_id='default'");
        int chunks = count("SELECT COUNT(*) FROM kb_chunk");
        int retrievalCases = count("SELECT COUNT(*) FROM kb_rag_eval WHERE case_type='RETRIEVAL'");
        int multiHopCases = count("SELECT COUNT(*) FROM kb_rag_eval WHERE case_type='MULTI_HOP'");
        int multiHopRefCases = count("SELECT COUNT(*) FROM kb_rag_eval WHERE case_type='MULTI_HOP_REF'");
        int annotatedDocuments = count("SELECT COUNT(*) FROM kb_deep_annotation WHERE status='VERIFIED'");
        int annotatedEntities = count("SELECT COALESCE(SUM(entity_count),0) FROM kb_deep_annotation WHERE status='VERIFIED'");
        int annotatedRelations = count("SELECT COALESCE(SUM(relation_count),0) FROM kb_deep_annotation WHERE status='VERIFIED'");
        int openGaps = count("SELECT COUNT(*) FROM kb_content_gap WHERE status='OPEN'");
        double p95Ms = p95LatencyMs();

        // 质量必须在“用例存在”之外单独验证：显式书名号引用考的是实体链接，
        // 不能拿它证明开放式多跳；这里实跑自然问法用例，命中率低就继续 DEFER。
        double multiHopHitRate = 0;
        double multiHopMrr = 0;
        int multiHopEvalCases = 0;
        try {
            var multiHopEval = kbEvalService.runMultiHop("default", null, 10);
            multiHopEvalCases = multiHopEval.getTotal();
            multiHopHitRate = multiHopEval.getHitRate();
            multiHopMrr = multiHopEval.getMrr();
        } catch (Exception e) {
            log.warn("门禁开放式多跳评测失败 - err: {}", e.getMessage());
        }

        // 多文档共现只作观察项：topK=5 天然会跨文档，不能直接当成“多跳需求”。
        int sampledTraces = 0;
        int crossDocTraces = 0;
        List<String> summaries = jdbcTemplate.queryForList(
                "SELECT hit_summary FROM kb_retrieval_trace WHERE hit_summary IS NOT NULL ORDER BY id DESC LIMIT 500",
                String.class);
        for (String summary : summaries) {
            if (summary == null || summary.isBlank()) {
                continue;
            }
            sampledTraces++;
            Set<String> docIds = new HashSet<>();
            for (String part : summary.split(",")) {
                String trimmed = part.trim();
                int hash = trimmed.indexOf('#');
                docIds.add(hash > 0 ? trimmed.substring(0, hash) : trimmed);
            }
            if (docIds.size() >= 2) {
                crossDocTraces++;
            }
        }
        double crossDocRatio = sampledTraces == 0 ? 0 : round2((double) crossDocTraces / sampledTraces);

        List<Map<String, Object>> checks = new ArrayList<>();
        checks.add(check("多跳评测集", 10, multiHopCases, false,
                "至少 10 条自然问法 MULTI_HOP 用例；显式书名号引用用例单独归为 MULTI_HOP_REF，不计入门禁"));
        checks.add(check("开放式多跳召回", 0.70, multiHopHitRate, false,
                "自然问法必须同时召回两篇期望文档；低于 70% 说明检索链路还不能承担多跳"));
        checks.add(check("实体/关系抽取样本", 50, annotatedDocuments, false,
                "至少 50 篇文档完成实体关系标注，验证抽取质量"));
        // 延迟是“越低越好”：不能套用 actual >= threshold 的统一方向。
        checks.add(check("P95 延迟预算", 1000, p95Ms, true,
                "GraphRAG 会叠加遍历/二次检索，P95 需先压到 1s 内"));

        boolean recommended = checks.stream().allMatch(c -> Boolean.TRUE.equals(c.get("pass")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recommendation", recommended ? "GO" : "DEFER");
        result.put("summary", recommended
                ? "前置门禁与开放式多跳质量均达标，可进入轻量多跳试点"
                : "计数与延迟达标但开放式多跳召回不足，全量 GraphRAG 继续 DEFER");
        result.put("documents", documents);
        result.put("chunks", chunks);
        result.put("retrievalCases", retrievalCases);
        result.put("multiHopCases", multiHopCases);
        result.put("multiHopRefCases", multiHopRefCases);
        result.put("multiHopEvalCases", multiHopEvalCases);
        result.put("multiHopHitRate", multiHopHitRate);
        result.put("multiHopMrr", multiHopMrr);
        result.put("annotatedDocuments", annotatedDocuments);
        result.put("annotatedEntities", annotatedEntities);
        result.put("annotatedRelations", annotatedRelations);
        result.put("openGaps", openGaps);
        result.put("p95LatencyMs", p95Ms);
        result.put("sampledTraces", sampledTraces);
        result.put("crossDocTraceRatio", crossDocRatio);
        result.put("checks", checks);
        log.info("深度检索门禁 - recommendation: {}, checks: {}", result.get("recommendation"), checks);
        return result;
    }

    private Map<String, Object> check(String name, double threshold, double actual,
                                      boolean lowerIsBetter, String note) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("threshold", threshold);
        item.put("actual", actual);
        item.put("pass", lowerIsBetter ? (actual > 0 && actual <= threshold) : actual >= threshold);
        item.put("note", note);
        return item;
    }

    private int count(String sql) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    /** 取最近 100 条检索 trace 的 P95；窗口与线上容量评估口径一致，避免历史慢请求长期污染当前结论。 */
    private double p95LatencyMs() {
        List<Long> values = jdbcTemplate.queryForList(
                "SELECT elapsed_ms FROM kb_retrieval_trace WHERE elapsed_ms IS NOT NULL ORDER BY id DESC LIMIT 100",
                Long.class);
        if (values.isEmpty()) {
            return 0;
        }
        values.sort(Long::compareTo);
        int idx = Math.min(values.size() - 1, (int) Math.ceil(values.size() * 0.95) - 1);
        return values.get(idx);
    }

    private double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
