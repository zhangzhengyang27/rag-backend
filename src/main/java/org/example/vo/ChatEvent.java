package org.example.vo;

import org.springframework.http.codec.ServerSentEvent;

/**
 * 对话流式事件：经标准 SSE（text/event-stream）协议推送给前端。
 * <ul>
 *   <li>conversation：已确定/生成的对话 ID</li>
 *   <li>phase：阶段提示（如"正在分析您的意图…"）</li>
 *   <li>intention：意图已识别，content 为意图标签</li>
 *   <li>token：AI 回复内容的增量文本</li>
 *   <li>done：本轮对话完成</li>
 *   <li>error：出错信息</li>
 * </ul>
 */
public record ChatEvent(String type, String content) {

    public static ChatEvent phase(String text) {
        return new ChatEvent("phase", text);
    }

    /**
     * 已确定/生成的对话ID，通常在首帧返回，供前端在后续请求中持续携带。
     */
    public static ChatEvent conversation(String id) {
        return new ChatEvent("conversation", id);
    }

    public static ChatEvent intention(String label) {
        return new ChatEvent("intention", label);
    }

    public static ChatEvent token(String text) {
        return new ChatEvent("token", text);
    }

    /**
     * 溯源引用：content 为检索命中的 JSON 数组（List&lt;KbChunkHit&gt; 序列化结果）。
     */
    public static ChatEvent sources(String json) {
        return new ChatEvent("sources", json);
    }

    public static ChatEvent done(String text) {
        return new ChatEvent("done", text);
    }

    public static ChatEvent error(String message) {
        return new ChatEvent("error", message);
    }

    /**
     * 转为 Spring 原生 SSE 事件，交由 WebFlux 的 ServerSentEventHttpMessageWriter 序列化。
     */
    public ServerSentEvent<String> toSse() {
        return ServerSentEvent.builder(content != null ? content : "").event(type).build();
    }
}
