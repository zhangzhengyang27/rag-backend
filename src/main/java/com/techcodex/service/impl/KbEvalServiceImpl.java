package com.techcodex.service.impl;

import lombok.extern.slf4j.Slf4j;
import com.techcodex.dto.KbChunkHit;
import com.techcodex.dto.SearchOptions;
import com.techcodex.entity.KbChatFeedbackEntity;
import com.techcodex.entity.KbContentGapEntity;
import com.techcodex.entity.RagEvalCaseEntity;
import com.techcodex.repository.KbChatFeedbackRepository;
import com.techcodex.repository.KbContentGapRepository;
import com.techcodex.repository.KbEvalReportRepository;
import com.techcodex.repository.RagEvalCaseRepository;
import com.techcodex.service.KbEvalService;
import com.techcodex.service.KbSearchService;
import com.techcodex.vo.KbEvalAbResult;
import com.techcodex.vo.KbEvalCaseView;
import com.techcodex.vo.KbEvalReportView;
import com.techcodex.vo.KbEvalResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * RAG 检索质量离线评测实现：对 Golden Set 逐个执行混合检索，判定返回片段是否覆盖期望关键字。
 */
@Slf4j
@Service
public class KbEvalServiceImpl implements KbEvalService {

    private final RagEvalCaseRepository evalCaseRepository;
    private final KbSearchService kbSearchService;
    private final KbEvalReportRepository reportRepository;
    private final KbChatFeedbackRepository feedbackRepository;
    /** 缺口回写直接走仓库而不是 ContentGapService：后者依赖本服务，反向注入会成环。 */
    private final KbContentGapRepository gapRepository;

    public KbEvalServiceImpl(RagEvalCaseRepository evalCaseRepository,
                             KbSearchService kbSearchService,
                             KbEvalReportRepository reportRepository,
                             KbChatFeedbackRepository feedbackRepository,
                             KbContentGapRepository gapRepository) {
        this.evalCaseRepository = evalCaseRepository;
        this.kbSearchService = kbSearchService;
        this.reportRepository = reportRepository;
        this.feedbackRepository = feedbackRepository;
        this.gapRepository = gapRepository;
    }

    @Override
    public KbEvalResult run(String libraryId, Integer limit, Integer topK) {
        return runEval(libraryId, limit, topK, null);
    }

    @Override
    public KbEvalAbResult runAb(String libraryId, String baselineLabel, SearchOptions baseline,
                                String variantLabel, SearchOptions variant,
                                Integer limit, Integer topK) {
        KbEvalResult base = runEval(libraryId, limit, topK, baseline);
        KbEvalResult var = runEval(libraryId, limit, topK, variant);
        log.info("RAG A/B 评测完成 - 空间 {}: {} vs {}: 命中率 {}% → {}%, MRR {} → {}, NDCG {} → {}",
                libraryId, baselineLabel, variantLabel,
                Math.round(base.getHitRate() * 1000) / 10.0,
                Math.round(var.getHitRate() * 1000) / 10.0,
                base.getMrr(), var.getMrr(), base.getNdcg(), var.getNdcg());
        return new KbEvalAbResult(baselineLabel, variantLabel, base, var);
    }

    @Override
    public List<KbEvalReportView> trend(String libraryId, int days) {
        int n = Math.max(1, Math.min(days, 90));
        return reportRepository.findByLibraryIdOrderByDateDesc(libraryId, PageRequest.of(0, n)).getContent().stream()
                .map(r -> new KbEvalReportView(r.getDate(), r.getCaseCount(), r.getHitRate(), r.getMrr(), r.getNdcg()))
                .toList();
    }

    @Override
    public KbEvalResult runMultiHop(String libraryId, Integer limit, Integer topK) {
        int topN = (topK == null || topK < 1) ? 10 : topK;
        List<RagEvalCaseEntity> cases = evalCaseRepository.findByCaseTypeOrderByIdAsc("MULTI_HOP");
        if (limit != null && limit > 0) {
            cases = cases.stream().limit(limit).toList();
        }
        int hit = 0;
        double mrrSum = 0;
        double ndcgSum = 0;
        double precisionSum = 0;
        double recallSum = 0;
        int ndcgCaseCount = 0;
        List<KbEvalResult.CaseResult> details = new ArrayList<>(cases.size());
        for (RagEvalCaseEntity c : cases) {
            List<String> kws = splitKeywords(c.getExpectedKeywords());
            List<Long> expectedDocIds = splitDocIds(c.getExpectedDocIds());
            List<String> titles = new ArrayList<>();
            List<KbChunkHit> results = null;
            boolean ok = false;
            int firstHitRank = 0;
            double ndcg = 0;
            try {
                // 自然多跳先做确定性查询分解：原句 + 子查询各自召回，再在文档级 RRF。
                // 这是“轻量多跳”验证：不引入 LLM 改写和全量 GraphRAG，仍能覆盖并列概念。
                List<List<com.techcodex.dto.KbChunkHit>> runs = new ArrayList<>();
                for (String subQuery : com.techcodex.service.KbQueryDecomposer.decompose(c.getQuestion())) {
                    runs.add(kbSearchService.searchAs(libraryId, null, subQuery, topN, null, null,
                            com.techcodex.dto.SearchOptions.of().topK(topN).finalTopK(topN).recordTrace(false)));
                }
                results = fuseDocLevel(runs, 60, topN);
                java.util.Set<Long> returnedDocs = new java.util.HashSet<>();
                for (int i = 0; i < results.size(); i++) {
                    KbChunkHit h = results.get(i);
                    titles.add(h.getTitle());
                    if (h.getDocId() != null) {
                        returnedDocs.add(h.getDocId());
                        if (expectedDocIds.contains(h.getDocId()) && firstHitRank == 0) {
                            firstHitRank = i + 1;
                        }
                    }
                }
                // 多跳看的是“文档级覆盖”，不要求某一个分块同时出现全部关键词：
                // 父子分块和长文档会把同一主题拆到多个块，块级全词命中会把正确的双文档召回误判为失败。
                // expectedKeywords 仍用于用例语义标注和人工复核，不参与 hit 判定。
                ok = expectedDocIds.stream().distinct().allMatch(returnedDocs::contains);
                if (!expectedDocIds.isEmpty()) {
                    ndcg = ndcgAtK(results, expectedDocIds);
                    ndcgSum += ndcg;
                    precisionSum += contextPrecision(results, expectedDocIds);
                    recallSum += contextRecall(results, expectedDocIds);
                    ndcgCaseCount++;
                }
            } catch (Exception e) {
                log.warn("多跳评测用例检索异常 - caseId: {}, err: {}", c.getId(), e.getMessage());
            }
            if (ok) {
                hit++;
            }
            if (firstHitRank > 0) {
                mrrSum += 1.0 / firstHitRank;
            }
            List<String> missing = missingKeywords(c.getExpectedKeywords(), results);
            details.add(new KbEvalResult.CaseResult(c.getId(), c.getQuestion(),
                    c.getExpectedKeywords(), ok, titles.size(), firstHitRank, titles, ndcg, missing));
        }
        int total = cases.size();
        double rate = total == 0 ? 0 : (double) hit / total;
        double mrr = total == 0 ? 0 : mrrSum / total;
        double ndcg = ndcgCaseCount == 0 ? 0 : ndcgSum / ndcgCaseCount;
        double contextPrecision = ndcgCaseCount == 0 ? 0 : precisionSum / ndcgCaseCount;
        double contextRecall = ndcgCaseCount == 0 ? 0 : recallSum / ndcgCaseCount;
        log.info("多跳评测完成 - 用例: {}, 命中: {}, 命中率: {}%, MRR: {}, NDCG: {}, CP: {}, CR: {}", total, hit,
                Math.round(rate * 1000) / 10.0, mrr, ndcg, contextPrecision, contextRecall);
        return new KbEvalResult(total, hit, total - hit, rate, mrr, ndcg, contextPrecision, contextRecall, details);
    }

    /** 单组评测：以显式空间身份检索（评测线程无登录态，不能读 SecurityContext）；options 为 null 或字段为 null 时沿用全局检索配置。 */
    private KbEvalResult runEval(String libraryId, Integer limit, Integer topK, SearchOptions options) {
        int topN = (topK == null || topK < 1) ? 5 : topK;
        // 标准评测也不写 trace：离线用例是合成流量，不能混进线上 P95 样本。
        if (options == null) {
            options = SearchOptions.of();
        }
        options.recordTrace(false);
        // Golden Set 固定只跑检索类用例；格式/引用类（如“回答中不要出现 [n]”）不计入
        // HitRate，否则会把“回答格式”与“检索召回”混成一个不可解释的指标。
        List<RagEvalCaseEntity> retrievalCases =
                evalCaseRepository.findByCaseTypeOrderByIdAsc("RETRIEVAL");
        // 兼容 V7 之前写入/外部导入的旧用例：case_type 为空的按检索类处理，
        // 避免历史数据在升级后被静默漏跑。
        if (retrievalCases.isEmpty()) {
            retrievalCases = evalCaseRepository.findAll().stream()
                    .filter(c -> c.getCaseType() == null || "RETRIEVAL".equals(c.getCaseType()))
                    .toList();
        }
        List<RagEvalCaseEntity> cases = limit != null && limit > 0
                ? retrievalCases.stream().limit(limit).toList()
                : retrievalCases;

        int hit = 0;
        int miss = 0;
        double mrrSum = 0;
        double ndcgSum = 0;
        double precisionSum = 0;
        double recallSum = 0;
        int ndcgCaseCount = 0;
        List<KbEvalResult.CaseResult> details = new ArrayList<>(cases.size());
        for (RagEvalCaseEntity c : cases) {
            boolean ok = false;
            int firstHitRank = 0; // 首个「含任一期望关键词」分块的排名（1-based），0 表示未命中
            List<String> titles = new ArrayList<>();
            List<Long> expectedDocIds = splitDocIds(c.getExpectedDocIds());
            double ndcg = 0;
            List<KbChunkHit> results = null;
            try {
                results = kbSearchService.searchAs(libraryId, null, c.getQuestion(), topN, null, null, options);
                List<String> kws = splitKeywords(c.getExpectedKeywords());
                for (int i = 0; i < results.size(); i++) {
                    KbChunkHit h = results.get(i);
                    titles.add(h.getTitle());
                    // 严格口径：单个分块覆盖全部期望关键字 → 命中（保留原有定义）
                    if (!ok && matchesKeywords(h.getText(), kws)) {
                        ok = true;
                    }
                    // 宽松口径：最早出现「任一关键词」的位置，用于 MRR 排序质量评估
                    if (firstHitRank == 0 && matchesAnyKeyword(h.getText(), kws)) {
                        firstHitRank = i + 1;
                    }
                }
                // NDCG@K：按返回片段命中期望文档的排序质量计算（仅用例声明了期望文档时）
                if (!expectedDocIds.isEmpty()) {
                    ndcg = ndcgAtK(results, expectedDocIds);
                    ndcgSum += ndcg;
                    // RAGAS 口径的检索两项：Context Precision=相关分块/返回分块；
                    // Context Recall=被召回期望文档/期望文档总数。
                    precisionSum += contextPrecision(results, expectedDocIds);
                    recallSum += contextRecall(results, expectedDocIds);
                    ndcgCaseCount++;
                }
            } catch (Exception e) {
                log.warn("评测用例检索异常 - caseId: {}, err: {}", c.getId(), e.getMessage());
            }
            if (ok) {
                hit++;
            } else {
                miss++;
            }
            if (firstHitRank > 0) {
                mrrSum += 1.0 / firstHitRank;
            }
            // 失败归因：期望关键字中在所有返回片段里都未出现的（区分「检索不到」与「排序靠后」）
            List<String> missing = missingKeywords(c.getExpectedKeywords(), results);
            details.add(new KbEvalResult.CaseResult(c.getId(), c.getQuestion(),
                    c.getExpectedKeywords(), ok, titles.size(), firstHitRank, titles, ndcg, missing));
        }
        int total = cases.size();
        double rate = total == 0 ? 0 : (double) hit / total;
        double mrr = total == 0 ? 0 : mrrSum / total;
        double ndcg = ndcgCaseCount == 0 ? 0 : ndcgSum / ndcgCaseCount;
        double contextPrecision = ndcgCaseCount == 0 ? 0 : precisionSum / ndcgCaseCount;
        double contextRecall = ndcgCaseCount == 0 ? 0 : recallSum / ndcgCaseCount;
        log.info("RAG 评测完成 - 用例: {}, 命中: {}, 命中率: {}%, MRR: {}, NDCG: {}, CP: {}, CR: {}", total, hit,
                Math.round(rate * 1000) / 10.0, mrr, ndcg, contextPrecision, contextRecall);
        closeResolvedGaps(details);
        return new KbEvalResult(total, hit, miss, rate, mrr, ndcg, contextPrecision, contextRecall, details);
    }

    /**
     * F-07 缺口闭环回写：缺口转评测用例时只写了 evalCaseId，这里把评测命中结果写回缺口状态。
     *
     * 规则：命中即 RESOLVED；未命中不改状态——单次检索失败可能只是 topK 或排序抖动，
     * 不该把已规划的缺口重新打开。人工 IGNORED/TRIAGED 的结论也不覆盖，
     * 只有尚未 RESOLVED 的行会被关闭。
     */
    private void closeResolvedGaps(List<KbEvalResult.CaseResult> details) {
        if (details == null || details.isEmpty()) return;
        int closed = 0;
        String stamp = "评测命中自动关闭 " + LocalDate.now();
        for (KbEvalResult.CaseResult detail : details) {
            if (!detail.isHit() || detail.getCaseId() == null) continue;
            for (KbContentGapEntity gap : gapRepository.findByEvalCaseId(detail.getCaseId())) {
                if ("RESOLVED".equals(gap.getStatus())) continue;
                gap.setStatus("RESOLVED");
                String note = gap.getNote() == null ? "" : gap.getNote();
                String merged = note.isEmpty() ? stamp : note + " | " + stamp;
                // note 列长 1000：超过时保留最近的尾部，避免把本次评测结论截掉
                gap.setNote(merged.length() > 1000 ? merged.substring(merged.length() - 1000) : merged);
                gapRepository.save(gap);
                closed++;
            }
        }
        if (closed > 0) {
            log.info("内容缺口回写 - 评测命中自动关闭: {}", closed);
        }
    }

    /** Context Precision：返回的 Top-K 分块中，有多少来自期望文档。 */
    private double contextPrecision(List<KbChunkHit> results, List<Long> expectedDocIds) {
        if (results == null || results.isEmpty()) {
            return 0;
        }
        java.util.Set<Long> expected = new java.util.HashSet<>(expectedDocIds);
        long relevant = results.stream().filter(h -> h.getDocId() != null && expected.contains(h.getDocId())).count();
        return (double) relevant / results.size();
    }

    /** Context Recall：期望文档中至少被一个返回分块召回的比例。 */
    private double contextRecall(List<KbChunkHit> results, List<Long> expectedDocIds) {
        if (expectedDocIds.isEmpty()) {
            return 0;
        }
        java.util.Set<Long> returned = new java.util.HashSet<>();
        if (results != null) {
            results.forEach(h -> {
                if (h.getDocId() != null) {
                    returned.add(h.getDocId());
                }
            });
        }
        long found = expectedDocIds.stream().distinct().filter(returned::contains).count();
        return (double) found / expectedDocIds.stream().distinct().count();
    }

    /**
     * 计算单个用例的 NDCG@K（标准折扣累积累积收益）。
     *
     * <p>以「返回片段命中的期望文档 id」作为相关度标记（命中=1，未命中=0），
     * 对前 K 个返回片段计算 DCG；IDCG 按全部期望文档的理想排序计算（上限为返回条数），
     * 期望文档未召回会被扣分——这使得 NDCG 同时反映召回与排序质量。
     * 返回空列表视为无结果，贡献 0。</p>
     */
    private double ndcgAtK(List<KbChunkHit> results, List<Long> expectedDocIds) {
        if (results == null || results.isEmpty()) {
            return 0d;
        }
        java.util.Set<Long> expected = new java.util.HashSet<>(expectedDocIds);
        java.util.Set<Long> seenDocs = new java.util.HashSet<>();
        double dcg = 0;
        int hits = 0;
        for (int i = 0; i < results.size(); i++) {
            Long docId = results.get(i).getDocId();
            // 同一文档常被切成多个分块：NDCG 只按文档计一次相关，否则重复分块会把 DCG
            // 抬到大于 IDCG（历史上出现过 NDCG > 1 的失真）。
            if (docId != null && expected.contains(docId) && seenDocs.add(docId)) {
                dcg += 1d / (Math.log(i + 2) / Math.log(2)); // rel_i / log2(i+1)，i 从 0 起
                hits++;
            }
        }
        if (hits == 0) {
            return 0d;
        }
        // 理想排序：全部期望文档都排在最前（上限为返回条数）；期望文档缺失会被扣分
        double idcg = 0;
        int idealCount = Math.min(expected.size(), results.size());
        for (int i = 0; i < idealCount; i++) {
            idcg += 1d / (Math.log(i + 2) / Math.log(2));
        }
        return idcg == 0 ? 0 : dcg / idcg;
    }

    /** 拆分期望命中文档 id（逗号分隔），忽略空串。 */
    private List<Long> splitDocIds(String expectedDocIds) {
        List<Long> ids = new ArrayList<>();
        if (expectedDocIds == null || expectedDocIds.isBlank()) {
            return ids;
        }
        for (String part : expectedDocIds.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                try {
                    ids.add(Long.parseLong(t));
                } catch (NumberFormatException e) {
                    log.warn("评测用例期望文档 id 非法，已忽略 - value: {}", t);
                }
            }
        }
        return ids;
    }

    @Override
    public Long addCase(String question, String expectedKeywords, String note, String createdBy) {
        return addCase(question, expectedKeywords, null, note, createdBy);
    }

    @Override
    public Long addCase(String question, String expectedKeywords, String expectedDocIds, String note, String createdBy) {
        return addCase(question, expectedKeywords, expectedDocIds, note, createdBy, "RETRIEVAL");
    }

    @Override
    public Long addCase(String question, String expectedKeywords, String expectedDocIds, String note,
                        String createdBy, String caseType) {
        RagEvalCaseEntity entity = new RagEvalCaseEntity();
        entity.setQuestion(question);
        entity.setExpectedKeywords(expectedKeywords);
        entity.setExpectedDocIds(expectedDocIds);
        entity.setNote(note);
        entity.setCreatedBy(createdBy);
        entity.setCaseType(normalizeCaseType(caseType));
        return evalCaseRepository.save(entity).getId();
    }

    /** 只允许两类，未知值回退检索类，避免脏值进入评测统计。 */
    private String normalizeCaseType(String caseType) {
        if (caseType == null || caseType.isBlank()) {
            return "RETRIEVAL";
        }
        String value = caseType.trim().toUpperCase(Locale.ROOT);
        if (value.equals("ANSWER_FORMAT") || value.equals("MULTI_HOP")
                || value.equals("MULTI_HOP_REF")) {
            return value;
        }
        return "RETRIEVAL";
    }

    @Override
    public Page<KbEvalCaseView> listCases(Pageable page) {
        return evalCaseRepository.findAllByOrderByIdDesc(page)
                .map(this::toCaseView);
    }

    @Override
    public void deleteCase(Long id) {
        RagEvalCaseEntity entity = evalCaseRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("评测用例不存在"));
        evalCaseRepository.delete(entity);
    }

    @Override
    public Long addCaseFromFeedback(String libraryId, Long feedbackId, String expectedKeywords, String note, String createdBy) {
        if (expectedKeywords == null || expectedKeywords.isBlank()) {
            throw new IllegalArgumentException("期望命中关键字不能为空");
        }
        KbChatFeedbackEntity feedback = feedbackRepository.findById(feedbackId)
                .orElseThrow(() -> new IllegalArgumentException("反馈不存在"));
        if (libraryId != null && !libraryId.equals(feedback.getLibraryId())) {
            throw new IllegalArgumentException("反馈不属于当前空间");
        }
        if (!"DOWN".equals(feedback.getRating())) {
            throw new IllegalArgumentException("仅点踩反馈可回流为评测用例");
        }
        String question = feedback.getQuestion() == null || feedback.getQuestion().isBlank()
                ? "（反馈缺少问题快照，请补充）"
                : feedback.getQuestion();
        String resolvedNote = (note == null || note.isBlank())
                ? buildFeedbackNote(feedback)
                : note + "（回流自点踩反馈#" + feedbackId + "）";
        log.info("点踩反馈回流为评测用例 - feedbackId: {}, createdBy: {}", feedbackId, createdBy);
        return addCase(question, expectedKeywords.trim(), null, resolvedNote, createdBy);
    }

    /** 回流备注缺省生成：反馈来源 + 用户评语，保留追溯线索。 */
    private String buildFeedbackNote(KbChatFeedbackEntity feedback) {
        StringBuilder sb = new StringBuilder("回流自点踩反馈#").append(feedback.getId());
        if (feedback.getComment() != null && !feedback.getComment().isBlank()) {
            sb.append("，用户评语：").append(feedback.getComment());
        }
        return sb.toString();
    }

    private KbEvalCaseView toCaseView(RagEvalCaseEntity e) {
        return KbEvalCaseView.builder()
                .id(e.getId())
                .question(e.getQuestion())
                .expectedKeywords(e.getExpectedKeywords())
                .expectedDocIds(e.getExpectedDocIds())
                .caseType(e.getCaseType())
                .note(e.getNote())
                .createdBy(e.getCreatedBy())
                .createdDate(e.getCreatedDate())
                .build();
    }

    /** 拆分期望关键字（逗号分隔，大小写不敏感）。 */
    private List<String> splitKeywords(String expectedKeywords) {
        List<String> kws = new ArrayList<>();
        if (expectedKeywords == null || expectedKeywords.isBlank()) {
            return kws;
        }
        for (String part : expectedKeywords.split(",")) {
            String k = part.trim().toLowerCase(Locale.ROOT);
            if (!k.isEmpty()) {
                kws.add(k);
            }
        }
        return kws;
    }

    /** 判定文本是否命中全部期望关键字（大小写不敏感，支持英文术语）。 */
    private boolean matchesKeywords(String text, List<String> kws) {
        if (text == null || kws.isEmpty()) {
            return false;
        }
        String lowerText = text.toLowerCase(Locale.ROOT);
        for (String k : kws) {
            if (!lowerText.contains(k)) {
                return false;
            }
        }
        return true;
    }

    /** 判定文本是否命中任一期望关键字（用于宽松口径的 MRR 计算）。 */
    private boolean matchesAnyKeyword(String text, List<String> kws) {
        if (text == null || kws.isEmpty()) {
            return false;
        }
        String lowerText = text.toLowerCase(Locale.ROOT);
        for (String k : kws) {
            if (lowerText.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 失败归因：返回「在所有返回片段中都不存在」的期望关键字（小写）。
     *
     * <p>非空说明召回缺失（检索不到）；为空但用例未命中则说明关键字虽都在返回池里、
     * 但没有单个片段同时覆盖全部（排序/分块问题）。检索结果为空或异常时按全部缺失处理。</p>
     */
    private List<String> missingKeywords(String expectedKeywords, List<KbChunkHit> results) {
        List<String> kws = splitKeywords(expectedKeywords);
        if (kws.isEmpty() || results == null || results.isEmpty()) {
            return kws;
        }
        List<String> missing = new ArrayList<>();
        for (String kw : kws) {
            boolean found = false;
            for (KbChunkHit h : results) {
                if (h.getText() != null && h.getText().toLowerCase(Locale.ROOT).contains(kw)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                missing.add(kw);
            }
        }
        return missing;
    }
    /**
     * 文档级 RRF：每个子查询先按“文档首次出现”排名，避免同一长文档的多个分块
     * 占据多个名次、把另一个概念的文档挤出 TopK。返回每个文档最佳命中块。
     */
    private List<com.techcodex.dto.KbChunkHit> fuseDocLevel(
            List<List<com.techcodex.dto.KbChunkHit>> runs, int k, int topK) {
        java.util.Map<Long, Double> scores = new java.util.HashMap<>();
        java.util.Map<Long, com.techcodex.dto.KbChunkHit> best = new java.util.HashMap<>();
        for (List<com.techcodex.dto.KbChunkHit> run : runs) {
            java.util.Set<Long> seenDocs = new java.util.HashSet<>();
            int rank = 0;
            for (com.techcodex.dto.KbChunkHit hit : run) {
                if (hit.getDocId() == null || !seenDocs.add(hit.getDocId())) {
                    continue;
                }
                rank++;
                scores.merge(hit.getDocId(), 1.0 / (k + rank), Double::sum);
                best.putIfAbsent(hit.getDocId(), hit);
            }
        }
        List<java.util.Map.Entry<Long, Double>> ranked = new ArrayList<>(scores.entrySet());
        ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        List<com.techcodex.dto.KbChunkHit> result = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, ranked.size()); i++) {
            result.add(best.get(ranked.get(i).getKey()));
        }
        return result;
    }

}
