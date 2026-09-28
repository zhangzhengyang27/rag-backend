package org.example.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Date;

/**
 * 对话（会话）元信息，用于前端会话列表。
 */
@Data
@AllArgsConstructor(staticName = "of")
public class ConversationVo {

    private String conversationId;

    /** 会话标题：由首条用户消息截取得到 */
    private String title;

    /** 该对话内消息条数 */
    private long messageCount;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date updatedAt;
}