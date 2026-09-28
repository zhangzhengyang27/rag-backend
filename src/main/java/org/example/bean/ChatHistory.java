package org.example.bean;

import lombok.Data;

/**
 * 对话历史记录（工具函数返回给大模型的结构）。
 */
@Data
public class ChatHistory {

    /**
     * 角色：user=用户消息，ai=助手消息
     */
    private String role;

    /**
     * 会话内容
     */
    private String content;
}
