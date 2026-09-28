package org.example.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.Comment;

/**
 * 审计日志：管理操作（导入/删除/重建/配额变更等）留痕。
 */
@EqualsAndHashCode(callSuper = true)
@Table(name = "kb_audit_log")
@Entity
@Data
@Comment("审计日志")
public class AuditLogEntity extends BaseEntity {

    @Comment("空间业务键（平台管理员可跨空间查；空表示平台级操作）")
    @Column(length = 64)
    private String libraryId;

    @Comment("操作人")
    @Column(length = 64)
    private String operator;

    @Comment("操作类型：UPLOAD/IMPORT_URL/IMPORT_DIR/DELETE/REBUILD_INDEX/UPDATE_QUOTA 等")
    @Column(length = 32)
    private String action;

    @Comment("操作对象类型：DOCUMENT/DIR/LIBRARY 等")
    @Column(length = 32)
    private String targetType;

    @Comment("操作对象ID（可空，如目录导入为 null）")
    private Long targetId;

    @Comment("操作详情")
    @Column(length = 500)
    private String detail;
}
