package org.example.service;

import org.example.vo.AuditLogView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 审计日志服务：管理操作留痕 + 查询。
 */
public interface AuditLogService {

    /**
     * 记录一条审计日志（旁路写入，失败仅记日志不影响主流程）。
     *
     * @param libraryId   空间（平台级操作可空）
     * @param operator   操作人
     * @param action     操作类型（UPLOAD/IMPORT_URL/IMPORT_DIR/DELETE/REBUILD_INDEX/UPDATE_QUOTA 等）
     * @param targetType 操作对象类型（DOCUMENT/DIR/LIBRARY 等）
     * @param targetId   操作对象ID（可空）
     * @param detail     操作详情（超长自动截断）
     */
    void record(String libraryId, String operator, String action, String targetType, Long targetId, String detail);

    /**
     * 分页查询审计日志；libraryId 为空查全部（平台管理员跨空间），action 为空不过滤。
     */
    Page<AuditLogView> query(String libraryId, String action, Pageable page);
}
