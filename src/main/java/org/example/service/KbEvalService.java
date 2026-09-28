package org.example.service;

import org.example.dto.SearchOptions;
import org.example.vo.KbEvalAbResult;
import org.example.vo.KbEvalCaseView;
import org.example.vo.KbEvalReportView;
import org.example.vo.KbEvalResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * RAG 检索质量离线评测：基于 Golden Set 计算召回命中率，用于量化检索改进效果。
 */
public interface KbEvalService {

    /**
     * 运行评测：对全部（或前 limit 个）评测用例执行检索，判定是否命中期望关键字。
     * 评测以显式空间身份执行（不读 SecurityContext），供管理端与定时调度共用。
     *
     * @param libraryId 评测目标空间 code
     * @param limit    最多评测的用例数（null/0 表示全部）
     * @param topK     每次检索返回的片段数（默认 5）
     * @return 评测汇总结果
     */
    KbEvalResult run(String libraryId, Integer limit, Integer topK);

    /**
     * 检索 A/B 对比：同用例集、两组检索参数（基线/变体）各跑一遍，输出对照与差值。
     *
     * <p>两组互不影响：通过 {@link SearchOptions} 参数快照临时覆盖检索配置，
     * 不改动全局 {@code KbConfig}，无共享状态泄漏。</p>
     *
     * @param libraryId      评测目标空间 code
     * @param baselineLabel 基线组标签（用于前端展示）
     * @param baseline      基线组参数覆盖（null/空字段表示沿用全局配置）
     * @param variantLabel  变体组标签
     * @param variant       变体组参数覆盖
     * @param limit         最多评测的用例数（null/0 表示全部）
     * @param topK          每次检索返回的片段数（默认 5）
     */
    KbEvalAbResult runAb(String libraryId, String baselineLabel, SearchOptions baseline,
                         String variantLabel, SearchOptions variant,
                         Integer limit, Integer topK);

    /**
     * 查询指定空间最近 N 天评测趋势（按日期倒序），用于管理页趋势图与回归观察。
     *
     * @param libraryId 空间 code
     * @param days     最多返回的天数（1~90，默认取 30 语义由调用方决定）
     */
    List<KbEvalReportView> trend(String libraryId, int days);

    /** 新增一个评测用例，返回用例 id。 */
    Long addCase(String question, String expectedKeywords, String note, String createdBy);

    /** 新增一个评测用例（含期望命中文档 id，供 NDCG 排序质量评估），返回用例 id。 */
    Long addCase(String question, String expectedKeywords, String expectedDocIds, String note, String createdBy);

    /** 评测用例分页（新增倒序），供管理端 Golden Set 维护。 */
    Page<KbEvalCaseView> listCases(Pageable page);

    /** 删除评测用例。 */
    void deleteCase(Long id);

    /**
     * 将一条点踩反馈回流为评测用例：问题取反馈快照，期望关键字由管理员给定；
     * 备注缺省记录反馈来源与用户评语，打通"反馈 → 用例 → 调优"质量闭环。
     *
     * @param libraryId       反馈所属空间（校验用，防止跨空间回流）
     * @param feedbackId      点踩反馈 id
     * @param expectedKeywords 期望命中关键字（逗号分隔，必填）
     * @param note            备注覆盖（为空时自动生成：反馈来源 + 用户评语）
     * @param createdBy       操作人
     * @return 新建用例 id
     */
    Long addCaseFromFeedback(String libraryId, Long feedbackId, String expectedKeywords, String note, String createdBy);
}
