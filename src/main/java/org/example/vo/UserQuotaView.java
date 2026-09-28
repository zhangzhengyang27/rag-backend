package org.example.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 管理端用户配额视图：个人知识库的用量与限额一览。
 * used 为当前值（文档数为累计，检索/问答为当日计数）；limit 为生效限额
 * （用户覆盖值或全局默认），limitCustom 标记是否为用户级覆盖。
 */
@Data
@Builder
public class UserQuotaView {

    private Long userId;

    private String username;

    private String nickname;

    /** ACTIVE / DISABLED */
    private String status;

    private long docUsed;
    private long docLimit;
    private boolean docLimitCustom;

    private long retrievalUsed;
    private long retrievalLimit;
    private boolean retrievalLimitCustom;

    private long chatUsed;
    private long chatLimit;
    private boolean chatLimitCustom;
}
