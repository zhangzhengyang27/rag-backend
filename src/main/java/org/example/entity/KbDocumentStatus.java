package org.example.entity;

/**
 * 知识库文档索引状态。
 */
public enum KbDocumentStatus {
    /** 待索引 */
    PENDING,
    /** 已索引 */
    INDEXED,
    /** 处理失败 */
    FAILED
}