package com.techcodex.service;

import com.techcodex.vo.ContentGapClusterView;
import com.techcodex.vo.ContentGapDetailView;
import com.techcodex.vo.ContentGapSummaryView;
import com.techcodex.vo.ContentGapView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * 内容缺口中心：承接问答链路的四类负向信号，聚合、聚类并流转到评测集。
 *
 * <p>信号写入是旁路：内部吞掉异常并记日志，绝不影响问答主链路。</p>
 */
public interface ContentGapService {

    /** 记录一次缺口信号；同 library + 归一化问题合并频次。 */
    void recordSignal(String libraryId, String userId, String conversationId, String question,
                      String signalType, List<Long> relatedDocIds, Long feedbackId, String detail);

    Page<ContentGapView> page(String libraryId, String status, String keyword, Pageable pageable);

    List<ContentGapView> top(String libraryId, int limit);

    ContentGapSummaryView summary(String libraryId);

    List<ContentGapClusterView> clusters(String libraryId);

    ContentGapDetailView detail(Long id, String libraryId);

    ContentGapView updateStatus(Long id, String libraryId, String status, String note);

    void batchStatus(List<Long> ids, String libraryId, String status);

    /** 缺口转评测用例：问题复用缺口原文，返回新建用例 id。 */
    Long convertToEval(Long id, String libraryId, String expectedKeywords, String note, String operator);

    /** 导出当前筛选结果为 CSV（UTF-8 BOM，Excel 可直接打开）。 */
    String exportCsv(String libraryId, String status, String keyword);
}
