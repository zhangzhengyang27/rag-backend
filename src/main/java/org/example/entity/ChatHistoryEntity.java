package org.example.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.Comment;

/**
 * 聊天历史记录。
 * role 取值统一为 user（用户消息）与 ai（助手消息）。
 */
@EqualsAndHashCode(callSuper = true)
@Table(name = "chat_history")
@Entity
@Data
@Comment("聊天历史")
public class ChatHistoryEntity extends BaseEntity {

    @Comment("空间业务键（组织隔离维度；存量数据回填 default）")
    @Column(length = 64)
    private String libraryId;

    @Comment("会话ID")
    private String sessionId;

    @Comment("对话ID：同一用户可拥有多个独立对话；历史旧数据为空")
    private String conversationId;

    @Comment("角色：user=用户消息，ai=助手消息")
    private String role;

    @Comment("消息内容")
    @Lob
    private String content;
}
