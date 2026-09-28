package org.example.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.Comment;

/**
 * 聊天用量统计：记录每次问答的 token 消耗（估算）、耗时与命中片段数，用于运营看板。
 * 轻量旁路写入，不影响问答主链路；token 为估算值（流式回答无真实用量回传）。
 */
@EqualsAndHashCode(callSuper = true)
@Table(name = "kb_chat_usage")
@Entity
@Data
@Comment("聊天用量统计")
public class ChatUsageEntity extends BaseEntity {

    @Comment("空间业务键（组织隔离维度；存量数据回填 default）")
    @Column(length = 64)
    private String libraryId;

    @Comment("用户ID")
    @Column(length = 64)
    private String userId;

    @Comment("对话ID")
    @Column(length = 255)
    private String conversationId;

    @Comment("用户问题")
    @Lob
    private String question;

    @Comment("回答字符数")
    private Integer answerCharCount;

    @Comment("输入 token 估算")
    private Integer inputTokens;

    @Comment("输出 token 估算")
    private Integer outputTokens;

    @Comment("总 token 估算（输入+输出）")
    private Integer totalTokens;

    @Comment("问答耗时（毫秒）")
    private Long elapsedMs;

    @Comment("检索命中片段数")
    private Integer hitCount;
}
