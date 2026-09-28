package org.example.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.Comment;

/**
 * 知识库运行时配置覆盖：一行一个配置键，值为管理端在线调整后的覆盖值；
 * 未覆盖的键回退到 properties 启动默认（见 RuntimeConfigStore）。
 */
@EqualsAndHashCode(callSuper = true)
@Table(name = "kb_runtime_config",
        uniqueConstraints = @UniqueConstraint(name = "uk_kb_cfg_key", columnNames = "cfgKey"),
        indexes = @Index(name = "idx_kb_cfg_key", columnList = "cfgKey"))
@Entity
@Data
@Comment("知识库运行时配置覆盖")
public class KbRuntimeConfigEntity extends BaseEntity {

    @Comment("配置键（如 retrieval.final-top-k）")
    @Column(name = "cfgKey", nullable = false, length = 128)
    private String cfgKey;

    @Comment("覆盖值（字符串形态，按键类型解析）")
    @Column(nullable = false, columnDefinition = "TEXT")
    private String cfgValue;

    @Comment("最后修改人")
    @Column(length = 64)
    private String updatedBy;
}
