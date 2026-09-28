package org.example.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.Comment;

/**
 * 系统用户实体：自建账号体系，密码以 BCrypt 加密存储。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "sys_user",
        uniqueConstraints = @UniqueConstraint(name = "uk_sys_user_username", columnNames = "username"))
public class UserEntity extends BaseEntity {

    @Column(nullable = false, length = 64)
    @Comment("用户名（登录账号）")
    private String username;

    @Column(nullable = false, length = 128)
    @Comment("密码（BCrypt 加密）")
    private String password;

    @Column(length = 64)
    @Comment("昵称")
    private String nickname;

    @Column(length = 20)
    @Comment("角色：USER=普通用户，ADMIN=管理员")
    private String role = "USER";

    @Comment("空间业务键（组织隔离维度；存量数据回填 default）")
    @Column(length = 64)
    private String libraryId;

    @Comment("状态：ACTIVE=正常，DISABLED=禁用（登录与鉴权均拒绝）；存量 null 视为 ACTIVE")
    @Column(length = 20)
    private String status = "ACTIVE";

    @Comment("个人知识库文档数限额覆盖（null=跟随全局配置 app.kb.library.user-doc-limit）")
    private Long docLimit;

    @Comment("个人知识库每日检索限额覆盖（null=跟随全局配置）")
    private Long retrievalLimit;

    @Comment("个人知识库每日问答限额覆盖（null=跟随全局配置）")
    private Long chatLimit;
}
