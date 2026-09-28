package org.example.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.Comment;

/**
 * 问答反馈表：用户对单条 AI 回答的点赞/点踩（可附评语）。
 * 点踩条目是评测集（Golden Set）的自然来源，构成"用户反馈 → 评测用例 → 检索调优"的质量闭环。
 */
@EqualsAndHashCode(callSuper = true)
@Table(name = "kb_chat_feedback", indexes = {
        @Index(name = "idx_kb_feedback_library", columnList = "libraryId"),
        @Index(name = "idx_kb_feedback_library_rating", columnList = "libraryId, rating")
})
@Entity
@Data
@Comment("问答反馈（点赞/点踩）")
public class KbChatFeedbackEntity extends BaseEntity {

    @Comment("空间业务键（组织隔离维度）")
    @Column(length = 64)
    private String libraryId;

    @Comment("反馈用户")
    @Column(length = 64)
    private String userId;

    @Comment("对话ID（可空：未随对话提交）")
    @Column(length = 64)
    private String conversationId;

    @Comment("评价：UP=点赞，DOWN=点踩")
    @Column(length = 10)
    private String rating;

    @Comment("反馈时的问题快照（截断存储，独立于聊天历史可追溯）")
    @Column(length = 2000)
    private String question;

    @Comment("反馈时的回答快照")
    @Lob
    private String answer;

    @Comment("用户评语（可空）")
    @Column(length = 1000)
    private String comment;
}
