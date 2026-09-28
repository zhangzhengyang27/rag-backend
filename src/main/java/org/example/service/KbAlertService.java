package org.example.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 运营告警服务：写告警（同类未处理去重）、分页查询、处理。
 */
public interface KbAlertService {

    /**
     * 记录一条告警；同类型已有未处理告警时跳过（去重，避免定时任务重复堆积）。
     *
     * @param libraryId 空间（default=公共知识库）
     * @param type      告警类型（EVAL_LOW_HITRATE / DOCS_SYNC_FAILED 等）
     * @param severity  严重级别（WARN / CRITICAL）
     * @param message   标题
     * @param detail    详情
     * @return 是否真正写入（false=已有同类未处理告警）
     */
    boolean raise(String libraryId, String type, String severity, String message, String detail);

    /** 告警分页（时间倒序）；resolved 为空查全部。 */
    Page<org.example.vo.KbAlertView> page(String libraryId, Boolean resolved, Pageable pageable);

    /** 当前未处理告警（总览页）。 */
    java.util.List<org.example.vo.KbAlertView> openAlerts(String libraryId);

    /** 处理告警（置 resolved 并记录处理人）。 */
    void resolve(Long id, String operator);
}
