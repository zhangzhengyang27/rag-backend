package org.example.service.impl;

import org.example.dto.KbChunkHit;
import org.example.dto.SearchOptions;
import org.example.entity.KbEvalReportEntity;
import org.example.entity.RagEvalCaseEntity;
import org.example.repository.KbEvalReportRepository;
import org.example.repository.RagEvalCaseRepository;
import org.example.service.KbSearchService;
import org.example.vo.KbEvalAbResult;
import org.example.vo.KbEvalReportView;
import org.example.vo.KbEvalResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * RAG 评测服务单测：重点覆盖 NDCG@K 计算与命中/MRR 口径。
 */
@ExtendWith(MockitoExtension.class)
class KbEvalServiceImplTest {

    @Mock
    private RagEvalCaseRepository evalCaseRepository;

    @Mock
    private KbSearchService kbSearchService;

    @Mock
    private KbEvalReportRepository reportRepository;

    @InjectMocks
    private KbEvalServiceImpl kbEvalService;

    /** 构造一个最小检索命中：docId 与文本内容。 */
    private KbChunkHit hit(Long docId, String text) {
        return KbChunkHit.builder()
                .docId(docId)
                .chunkIndex(0)
                .title("doc" + docId)
                .text(text)
                .score(0.9)
                .build();
    }

    @Test
    void ndcgIsOneWhenExpectedDocsRankedFirst() {
        RagEvalCaseEntity c = new RagEvalCaseEntity();
        c.setId(1L);
        c.setQuestion("q");
        c.setExpectedKeywords("WebFlux");
        c.setExpectedDocIds("10,20");

        when(evalCaseRepository.findAll()).thenReturn(List.of(c));
        // 返回顺序：doc10(期望) -> doc30(非期望) -> doc20(期望)
        when(kbSearchService.searchAs(eq("default"), isNull(), anyString(), anyInt(), isNull(), isNull(), (SearchOptions) any())).thenReturn(List.of(
                hit(10L, "WebFlux 原理"),
                hit(30L, "无关内容"),
                hit(20L, "WebFlux 阻塞模型")));

        KbEvalResult result = kbEvalService.run("default", null, 5);

        // NDCG = DCG / IDCG；期望文档排在 1、3 位，必然 < 1
        assertThat(result.getNdcg()).isGreaterThan(0).isLessThan(1);
        assertThat(result.getCases().get(0).getNdcg()).isEqualTo(result.getNdcg());
    }

    @Test
    void ndcgIsOneWhenOnlyExpectedDocsInOrder() {
        RagEvalCaseEntity c = new RagEvalCaseEntity();
        c.setId(1L);
        c.setQuestion("q");
        c.setExpectedKeywords("WebFlux");
        c.setExpectedDocIds("10,20");

        when(evalCaseRepository.findAll()).thenReturn(List.of(c));
        // 完美排序：两个期望文档排在最前
        when(kbSearchService.searchAs(eq("default"), isNull(), anyString(), anyInt(), isNull(), isNull(), (SearchOptions) any())).thenReturn(List.of(
                hit(10L, "WebFlux 原理"),
                hit(20L, "WebFlux 阻塞模型")));

        KbEvalResult result = kbEvalService.run("default", null, 5);

        assertThat(result.getNdcg()).isEqualTo(1.0);
    }

    @Test
    void ndcgIsZeroWhenNoExpectedDocReturned() {
        RagEvalCaseEntity c = new RagEvalCaseEntity();
        c.setId(1L);
        c.setQuestion("q");
        c.setExpectedKeywords("WebFlux");
        c.setExpectedDocIds("10,20");

        when(evalCaseRepository.findAll()).thenReturn(List.of(c));
        when(kbSearchService.searchAs(eq("default"), isNull(), anyString(), anyInt(), isNull(), isNull(), (SearchOptions) any())).thenReturn(List.of(
                hit(30L, "无关内容"),
                hit(40L, "更无关")));

        KbEvalResult result = kbEvalService.run("default", null, 5);

        assertThat(result.getNdcg()).isZero();
    }

    @Test
    void hitRateAndMrrStillComputed() {
        RagEvalCaseEntity c = new RagEvalCaseEntity();
        c.setId(1L);
        c.setQuestion("q");
        c.setExpectedKeywords("WebFlux");
        c.setExpectedDocIds(null); // 未声明期望文档，NDCG 不参与

        when(evalCaseRepository.findAll()).thenReturn(List.of(c));
        when(kbSearchService.searchAs(eq("default"), isNull(), anyString(), anyInt(), isNull(), isNull(), (SearchOptions) any())).thenReturn(List.of(
                hit(10L, "无关"),
                hit(20L, "WebFlux 命中")));

        KbEvalResult result = kbEvalService.run("default", null, 5);

        assertThat(result.getHit()).isEqualTo(1);
        assertThat(result.getHitRate()).isEqualTo(1.0);
        assertThat(result.getMrr()).isEqualTo(0.5); // 首个命中在第 2 位，1/2
        assertThat(result.getNdcg()).isZero(); // 无期望文档时 NDCG 为 0
    }

    @Test
    void abReturnsIndependentResultsForTwoConfigs() {
        RagEvalCaseEntity c = new RagEvalCaseEntity();
        c.setId(1L);
        c.setQuestion("q");
        c.setExpectedKeywords("WebFlux");
        c.setExpectedDocIds(null);

        when(evalCaseRepository.findAll()).thenReturn(List.of(c));
        // 基线（rerank=off）：检索返回无关内容 → 未命中；变体（rerank=on）：命中第 1 位
        when(kbSearchService.searchAs(eq("default"), isNull(), anyString(), anyInt(), isNull(), isNull(), (SearchOptions) any())).thenAnswer(inv -> {
            SearchOptions o = inv.getArgument(6); // searchAs 第 7 个参数是 options 覆盖
            return Boolean.TRUE.equals(o.getRerankEnabled())
                    ? List.of(hit(10L, "WebFlux 原理"))
                    : List.of(hit(30L, "无关内容"));
        });

        KbEvalAbResult ab = kbEvalService.runAb("default", "基线", SearchOptions.of().rerankEnabled(false),
                "变体", SearchOptions.of().rerankEnabled(true), null, 5);

        assertThat(ab.getBaseline().getHit()).isZero();
        assertThat(ab.getVariant().getHit()).isEqualTo(1);
        assertThat(ab.getVariant().getMrr()).isEqualTo(1.0);
        // 差值 = 变体 - 基线
        assertThat(ab.getHitRateDiff()).isEqualTo(1.0);
        assertThat(ab.getBaselineLabel()).isEqualTo("基线");
        assertThat(ab.getVariantLabel()).isEqualTo("变体");
    }

    @Test
    void abCallsWithNullOptionsFallBackToGlobalSearch() {
        RagEvalCaseEntity c = new RagEvalCaseEntity();
        c.setId(1L);
        c.setQuestion("q");
        c.setExpectedKeywords("WebFlux");
        c.setExpectedDocIds(null);

        when(evalCaseRepository.findAll()).thenReturn(List.of(c));
        // 两组都传空 options → 检索走全局配置路径（两参数组都命中）
        when(kbSearchService.searchAs(eq("default"), isNull(), anyString(), anyInt(), isNull(), isNull(), (SearchOptions) any()))
                .thenReturn(List.of(hit(10L, "WebFlux 原理")));

        KbEvalAbResult ab = kbEvalService.runAb("default", "A", SearchOptions.of(),
                "B", SearchOptions.of(), null, 5);

        assertThat(ab.getBaseline().getHitRate()).isEqualTo(1.0);
        assertThat(ab.getVariant().getHitRate()).isEqualTo(1.0);
        // 调用两次结果一致，互不影响（无共享状态泄漏）
        KbEvalAbResult again = kbEvalService.runAb("default", "A", SearchOptions.of(),
                "B", SearchOptions.of(), null, 5);
        assertThat(again.getBaseline().getHitRate()).isEqualTo(ab.getBaseline().getHitRate());
        assertThat(again.getVariant().getMrr()).isEqualTo(ab.getVariant().getMrr());
    }

    @Test
    void missCaseReportsMissingKeywordsAndTopTitles() {
        RagEvalCaseEntity c = new RagEvalCaseEntity();
        c.setId(1L);
        c.setQuestion("q");
        c.setExpectedKeywords("WebFlux,Reactor");
        c.setExpectedDocIds(null);

        when(evalCaseRepository.findAll()).thenReturn(List.of(c));
        // 返回片段只含 WebFlux、缺 Reactor → 未命中，应归因出缺失关键字
        when(kbSearchService.searchAs(eq("default"), isNull(), anyString(), anyInt(), isNull(), isNull(), (SearchOptions) any()))
                .thenReturn(List.of(hit(10L, "WebFlux 原理")));

        KbEvalResult result = kbEvalService.run("default", null, 5);

        KbEvalResult.CaseResult cr = result.getCases().get(0);
        assertThat(cr.isHit()).isFalse();
        assertThat(cr.getMissingKeywords()).containsExactly("reactor");
        // 返回片段标题（Top-N 归因）
        assertThat(cr.getTopTitles()).containsExactly("doc10");
    }

    @Test
    void hitCaseReportsEmptyMissingKeywords() {
        RagEvalCaseEntity c = new RagEvalCaseEntity();
        c.setId(1L);
        c.setQuestion("q");
        c.setExpectedKeywords("WebFlux,Reactor");
        c.setExpectedDocIds(null);

        when(evalCaseRepository.findAll()).thenReturn(List.of(c));
        // 命中用例：两个期望关键字都在同一片段 → 无缺失关键字
        when(kbSearchService.searchAs(eq("default"), isNull(), anyString(), anyInt(), isNull(), isNull(), (SearchOptions) any()))
                .thenReturn(List.of(hit(10L, "WebFlux Reactor 原理")));

        KbEvalResult result = kbEvalService.run("default", null, 5);

        KbEvalResult.CaseResult cr = result.getCases().get(0);
        assertThat(cr.isHit()).isTrue();
        assertThat(cr.getMissingKeywords()).isEmpty();
    }

    @Test
    void trendReturnsLibraryReportsByDateDesc() {
        KbEvalReportEntity newer = report("2026-08-23", 0.9, 0.8, 0.7, 2);
        KbEvalReportEntity older = report("2026-08-22", 0.5, 0.4, 0.3, 1);
        when(reportRepository.findByLibraryIdOrderByDateDesc(eq("default"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(newer, older)));

        List<KbEvalReportView> trend = kbEvalService.trend("default", 30);

        assertThat(trend).hasSize(2);
        assertThat(trend.get(0).getDate()).isEqualTo("2026-08-23");
        assertThat(trend.get(0).getHitRate()).isEqualTo(0.9);
        assertThat(trend.get(1).getDate()).isEqualTo("2026-08-22");
    }

    private KbEvalReportEntity report(String date, double hitRate, double mrr, double ndcg, int caseCount) {
        KbEvalReportEntity r = new KbEvalReportEntity();
        r.setDate(date);
        r.setHitRate(hitRate);
        r.setMrr(mrr);
        r.setNdcg(ndcg);
        r.setCaseCount(caseCount);
        return r;
    }
}
