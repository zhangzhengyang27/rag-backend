package org.example.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.dto.KbChunkHit;
import org.example.dto.SearchOptions;
import org.example.entity.KbChatFeedbackEntity;
import org.example.entity.RagEvalCaseEntity;
import org.example.repository.KbChatFeedbackRepository;
import org.example.repository.KbEvalReportRepository;
import org.example.repository.RagEvalCaseRepository;
import org.example.service.KbEvalService;
import org.example.service.KbSearchService;
import org.example.vo.KbEvalAbResult;
import org.example.vo.KbEvalCaseView;
import org.example.vo.KbEvalReportView;
import org.example.vo.KbEvalResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

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

    public KbEvalServiceImpl(RagEvalCaseRepository evalCaseRepository,
                             KbSearchService kbSearchService,
                             KbEvalReportRepository reportRepository,
                             KbChatFeedbackRepository feedbackRepository) {
        this.evalCaseRepository = evalCaseRepository;
        this.kbSearchService = kbSearchService;
        this.reportRepository = reportRepository;
        this.feedbackRepository = feedbackRepository;
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

    /** 单组评测：以显式空间身份检索（评测线程无登录态，不能读 SecurityContext）；options 为 null 或字段为 null 时沿用全局检索配置。 */
    private KbEvalResult runEval(String libraryId, Integer limit, Integer topK, SearchOptions options) {
        int topN = (topK == null || topK < 1) ? 5 : topK;
        List<RagEvalCaseEntity> cases = limit != null && limit > 0
                ? evalCaseRepository.findAll(PageRequest.of(0, limit)).getContent()
                : evalCaseRepository.findAll();

        int hit = 0;
        int miss = 0;
        double mrrSum = 0;
        double ndcgSum = 0;
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
        log.info("RAG 评测完成 - 用例: {}, 命中: {}, 命中率: {}%, MRR: {}, NDCG: {}", total, hit,
                Math.round(rate * 1000) / 10.0, mrr, ndcg);
        return new KbEvalResult(total, hit, miss, rate, mrr, ndcg, details);
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
        double dcg = 0;
        int hits = 0;
        for (int i = 0; i < results.size(); i++) {
            Long docId = results.get(i).getDocId();
            if (docId != null && expected.contains(docId)) {
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
        RagEvalCaseEntity entity = new RagEvalCaseEntity();
        entity.setQuestion(question);
        entity.setExpectedKeywords(expectedKeywords);
        entity.setExpectedDocIds(expectedDocIds);
        entity.setNote(note);
        entity.setCreatedBy(createdBy);
        return evalCaseRepository.save(entity).getId();
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
}
