package org.example.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.service.KbDeepDecisionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 门禁口径刻意保守：GraphRAG 需要“多跳评测集 + 实体/关系抽取 + 额外延迟预算”，
 * 三者缺一就先不立项，避免为了概念上线一个更慢、更难维护的检索链路。
 */
@Slf4j
@Service
public class KbDeepDecisionServiceImpl implements KbDeepDecisionService {

    private final JdbcTemplate jdbcTemplate;

    public KbDeepDecisionServiceImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Map<String, Object> gate() {
        int documents = count("SELECT COUNT(*) FROM kb_document WHERE library_id='default'");
        int chunks = count("SELECT COUNT(*) FROM kb_chunk");
        int retrievalCases = count("SELECT COUNT(*) FROM kb_rag_eval WHERE case_type='RETRIEVAL'");
        // 当前评测集没有“多跳”标签；这里如实返回 0，而不是用关键词猜一个假指标。
        int multiHopCases = count("SELECT COUNT(*) FROM kb_rag_eval WHERE case_type='MULTI_HOP'");
        int openGaps = count("SELECT COUNT(*) FROM kb_content_gap WHERE status='OPEN'");
        double p95Ms = p95LatencyMs();

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
                "至少 10 条 MULTI_HOP 用例，先证明需求真实存在"));
        checks.add(check("实体/关系抽取样本", 50, 0, false,
                "至少 50 篇文档完成实体关系标注，验证抽取质量"));
        // 延迟是“越低越好”：不能套用 actual >= threshold 的统一方向。
        checks.add(check("P95 延迟预算", 1000, p95Ms, true,
                "GraphRAG 会叠加遍历/二次检索，P95 需先压到 1s 内"));

        boolean recommended = checks.stream().allMatch(c -> Boolean.TRUE.equals(c.get("pass")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recommendation", recommended ? "GO" : "DEFER");
        result.put("summary", recommended
                ? "三项门禁均满足，可以小范围试点 GraphRAG"
                : "缺多跳评测集/实体样本或延迟无余量，先补数据再评估 GraphRAG");
        result.put("documents", documents);
        result.put("chunks", chunks);
        result.put("retrievalCases", retrievalCases);
        result.put("multiHopCases", multiHopCases);
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

    /** 取最近 200 条检索 trace 的 P95；没有样本时返回 0，门禁按不通过处理。 */
    private double p95LatencyMs() {
        List<Long> values = jdbcTemplate.queryForList(
                "SELECT elapsed_ms FROM kb_retrieval_trace WHERE elapsed_ms IS NOT NULL ORDER BY id DESC LIMIT 200",
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
