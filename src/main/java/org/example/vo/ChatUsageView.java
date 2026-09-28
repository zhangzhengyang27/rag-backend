package org.example.vo;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * 单条用量记录视图（管理端明细表用）。
 */
@Data
@Builder
public class ChatUsageView {

    private Long id;

    private String userId;

    private String conversationId;

    private String question;

    private Integer answerCharCount;

    private Integer inputTokens;

    private Integer outputTokens;

    private Integer totalTokens;

    private Long elapsedMs;

    private Integer hitCount;

    private Date createdDate;
}
