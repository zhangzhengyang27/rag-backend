package org.example.service;

import org.example.vo.RetrievalTraceView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 检索 Trace 可观测服务：查询混合检索的召回明细，供管理端诊断页使用。
 */
public interface KbTraceService {

    /**
     * Trace 分页（时间倒序）。
     *
     * @param libraryId 空间过滤（可空）
     * @param userId    用户过滤（可空）
     * @param keyword   关键字，模糊匹配原始查询/检索查询（可空）
     */
    Page<RetrievalTraceView> page(String libraryId, String userId, String keyword, Pageable pageable);
}
