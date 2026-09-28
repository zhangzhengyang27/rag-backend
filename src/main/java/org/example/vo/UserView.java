package org.example.vo;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * 管理端用户视图（用户管理列表）。
 */
@Data
@Builder
public class UserView {

    private Long id;

    private String username;

    private String nickname;

    /** USER / ADMIN */
    private String role;

    /** ACTIVE / DISABLED */
    private String status;

    private String libraryId;

    /** 个人知识库限额覆盖（null=跟随全局配置） */
    private Long docLimit;

    private Long retrievalLimit;

    private Long chatLimit;

    private Date createdDate;
}
